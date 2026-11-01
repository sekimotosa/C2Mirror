package io.github.sekimotosa.c2mirror.pairing

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 送受信できるバイトストリーム（usbmux の TCP 接続、またはその上の TLS） */
interface ByteStream {
    fun write(data: ByteArray)
    /** 届いているデータを返す。timeoutMs 内に何も来なければ null */
    fun readSome(timeoutMs: Long): ByteArray?
    fun close()
}

/** 指定バイト数を読み切る */
fun ByteStream.readFully(n: Int, timeoutMs: Long, pending: java.io.ByteArrayOutputStream): ByteArray {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (pending.size() < n) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw IOException("timeout reading $n bytes (have ${pending.size()})")
        readSome(left)?.let { pending.write(it) }
    }
    val all = pending.toByteArray()
    pending.reset()
    if (all.size > n) pending.write(all, n, all.size - n)
    return all.copyOf(n)
}

/**
 * usbmux プロトコル（iPhone の "Apple USB Multiplexor" インターフェース上で TCP 風の接続を多重化する）。
 * macOS / Linux では usbmuxd が担当している部分を、ペアリング（lockdownd との通信）に必要な範囲だけ実装する。
 *
 * パケット: [protocol u32][length u32]（v2 以降はさらに [magic 0xfeedface u32][tx_seq u16][rx_seq u16]）
 *          すべてビッグエンディアン。protocol 0=VERSION 1=CONTROL 2=SETUP 6=TCP。
 */
class UsbMux(
    private val usbWrite: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile var version = 0; private set
    private var txSeq = 0
    private var rxSeq = 0xFFFF
    private val writeLock = Any()
    private val versionReply = LinkedBlockingQueue<Int>()
    private val conns = ConcurrentHashMap<Int, MuxConnection>()
    private var nextPort = 1
    private val inbuf = java.io.ByteArrayOutputStream()
    @Volatile var packetsIn = 0L; private set
    @Volatile var packetsOut = 0L; private set

    // ---------- 送信 ----------

    internal fun sendPacket(protocol: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val hlen = if (version >= 2) 16 else 8
            val b = ByteBuffer.allocate(hlen + payload.size)
            b.putInt(protocol)
            b.putInt(hlen + payload.size)
            if (version >= 2) {
                b.putInt(MAGIC)
                b.putShort(txSeq.toShort())
                b.putShort(rxSeq.toShort())
                txSeq = (txSeq + 1) and 0xFFFF
            }
            b.put(payload)
            usbWrite(b.array())
            packetsOut++
        }
    }

    /** バージョン交渉（usbmuxd が接続直後に行うもの）。成功すると v2 なら SETUP を送る。 */
    fun handshake(timeoutMs: Long = 3000): Int {
        val vh = ByteBuffer.allocate(12).putInt(2).putInt(0).putInt(0).array()
        sendPacket(PROTO_VERSION, vh)
        val major = versionReply.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IOException("usbmux: no VERSION reply")
        version = major
        log("usbmux version $major")
        if (major >= 2) sendPacket(PROTO_SETUP, byteArrayOf(0x07))
        return major
    }

    fun connect(port: Int, timeoutMs: Long = 3000): MuxConnection {
        val sport = synchronized(this) { nextPort++ }
        val c = MuxConnection(this, sport, port)
        conns[sport] = c
        c.sendTcp(TH_SYN, ByteArray(0))
        if (!c.awaitConnected(timeoutMs)) {
            conns.remove(sport)
            throw IOException("usbmux: connect to port $port failed (${c.state})")
        }
        return c
    }

    internal fun remove(c: MuxConnection) { conns.remove(c.sport) }

    // ---------- 受信（USB 受信スレッドから） ----------

    fun onUsbData(buf: ByteArray, off: Int, len: Int) {
        inbuf.write(buf, off, len)
        while (true) {
            val a = inbuf.toByteArray()
            if (a.size < 8) return
            val bb = ByteBuffer.wrap(a)
            val protocol = bb.getInt(0)
            val length = bb.getInt(4)
            if (length < 8 || length > 1 shl 20) {
                log("usbmux: bad packet length $length, dropping ${a.size} bytes")
                inbuf.reset(); return
            }
            if (a.size < length) return
            inbuf.reset()
            if (a.size > length) inbuf.write(a, length, a.size - length)
            handlePacket(protocol, a, length)
        }
    }

    private fun handlePacket(protocol: Int, a: ByteArray, length: Int) {
        packetsIn++
        val bb = ByteBuffer.wrap(a, 0, length)
        var hlen = 8
        if (version >= 2 && protocol != PROTO_VERSION && length >= 16 && bb.getInt(8) == MAGIC) {
            hlen = 16
            // 相手の送信シーケンス番号を受信確認として返す
            synchronized(writeLock) { rxSeq = bb.getShort(12).toInt() and 0xFFFF }
        }
        when (protocol) {
            PROTO_VERSION -> {
                val major = if (length >= hlen + 4) bb.getInt(hlen) else 0
                versionReply.offer(major)
            }
            PROTO_CONTROL -> {
                // デバイスからのログ等。内容は機密の可能性があるので種別のみ記録
                log("usbmux: control packet (${length - hlen} bytes)")
            }
            PROTO_TCP -> {
                if (length < hlen + 20) return
                val th = hlen
                val sport = bb.getShort(th).toInt() and 0xFFFF
                val dport = bb.getShort(th + 2).toInt() and 0xFFFF
                val seq = bb.getInt(th + 4)
                val ack = bb.getInt(th + 8)
                val off = ((a[th + 12].toInt() and 0xF0) shr 4) * 4
                val flags = a[th + 13].toInt() and 0xFF
                val payload = a.copyOfRange(th + off, length)
                conns[dport]?.onTcp(sport, seq, ack, flags, payload)
                    ?: log("usbmux: packet for unknown port $dport flags=0x%02x".format(flags))
            }
            else -> log("usbmux: unknown protocol $protocol")
        }
    }

    fun closeAll() { conns.values.toList().forEach { it.close() } }

    companion object {
        const val MAGIC = 0xfeedface.toInt()
        const val PROTO_VERSION = 0
        const val PROTO_CONTROL = 1
        const val PROTO_SETUP = 2
        const val PROTO_TCP = 6
        const val TH_FIN = 0x01
        const val TH_SYN = 0x02
        const val TH_RST = 0x04
        const val TH_ACK = 0x10
        const val WINDOW = 131072
        const val MAX_SEGMENT = 16 * 1024
    }
}

/** usbmux 上の 1 本の TCP 接続 */
class MuxConnection internal constructor(
    private val mux: UsbMux,
    val sport: Int,
    val dport: Int,
) : ByteStream {
    enum class State { CONNECTING, CONNECTED, REFUSED, CLOSED }

    @Volatile var state = State.CONNECTING; private set
    private val connected = java.util.concurrent.CountDownLatch(1)
    private val rx = LinkedBlockingQueue<ByteArray>()
    private var txSeq = 0
    private var rxSeq = 0

    internal fun sendTcp(flags: Int, payload: ByteArray) {
        val b = ByteBuffer.allocate(20 + payload.size)
        synchronized(this) {
            b.putShort(sport.toShort())
            b.putShort(dport.toShort())
            b.putInt(txSeq)
            b.putInt(rxSeq)
            b.put((5 shl 4).toByte())
            b.put(flags.toByte())
            b.putShort((UsbMux.WINDOW shr 8).toShort())
            b.putShort(0) // checksum
            b.putShort(0) // urgent
            b.put(payload)
            txSeq += payload.size + (if (flags and UsbMux.TH_SYN != 0) 1 else 0)
        }
        mux.sendPacket(UsbMux.PROTO_TCP, b.array())
    }

    internal fun onTcp(sport: Int, seq: Int, ack: Int, flags: Int, payload: ByteArray) {
        if (flags and UsbMux.TH_RST != 0) {
            state = if (state == State.CONNECTING) State.REFUSED else State.CLOSED
            connected.countDown()
            rx.offer(EOF)
            mux.remove(this)
            return
        }
        if (state == State.CONNECTING) {
            if (flags and (UsbMux.TH_SYN or UsbMux.TH_ACK) == (UsbMux.TH_SYN or UsbMux.TH_ACK)) {
                synchronized(this) { rxSeq = seq + 1 }
                state = State.CONNECTED
                sendTcp(UsbMux.TH_ACK, ByteArray(0))
                connected.countDown()
            }
            return
        }
        if (payload.isNotEmpty()) {
            synchronized(this) { rxSeq = seq + payload.size }
            rx.offer(payload)
            sendTcp(UsbMux.TH_ACK, ByteArray(0))
        }
        if (flags and UsbMux.TH_FIN != 0) {
            state = State.CLOSED
            rx.offer(EOF)
        }
    }

    internal fun awaitConnected(timeoutMs: Long): Boolean =
        connected.await(timeoutMs, TimeUnit.MILLISECONDS) && state == State.CONNECTED

    override fun write(data: ByteArray) {
        if (state != State.CONNECTED) throw IOException("connection $state")
        var p = 0
        while (p < data.size) {
            val n = minOf(UsbMux.MAX_SEGMENT, data.size - p)
            sendTcp(UsbMux.TH_ACK, data.copyOfRange(p, p + n))
            p += n
        }
    }

    override fun readSome(timeoutMs: Long): ByteArray? {
        val b = rx.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (b === EOF) { rx.offer(EOF); throw IOException("connection closed by device") }
        return b
    }

    override fun close() {
        if (state == State.CONNECTED) runCatching { sendTcp(UsbMux.TH_RST or UsbMux.TH_ACK, ByteArray(0)) }
        state = State.CLOSED
        rx.offer(EOF)
        mux.remove(this)
    }

    private companion object { val EOF = ByteArray(0) }
}

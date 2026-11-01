package io.github.sekimotosa.c2mirror.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import io.github.sekimotosa.c2mirror.protocol.PacketWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QuickTime AV インターフェースの Bulk IN/OUT 転送。
 *
 * 受信は UsbRequest を複数同時にキューイングし（常に USB 転送が途切れないようにする）、
 * 完了したものから順にコピー→即再キュー→上位へ渡す。これにより1転送ごとの待ち時間を隠蔽し、
 * 大きな IDR フレームでも受信遅延を最小化する。
 */
class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : PacketWriter {

    private val writeLock = Any()
    private val running = AtomicBoolean(false)
    @Volatile var bytesRead = 0L; private set
    @Volatile var bytesWritten = 0L; private set
    @Volatile var writeErrors = 0; private set
    @Volatile var closed = false; private set

    /** インターフェースを Claim し、エンドポイントの HALT を解除する（参考実装 clearFeature 相当）。 */
    fun claim(): ClaimResult {
        if (!connection.claimInterface(usbInterface, true)) return ClaimResult(false, "claimInterface failed")
        val alt = if (usbInterface.alternateSetting != 0) connection.setInterface(usbInterface) else true
        val c1 = clearHalt(inEndpoint.address)
        val c2 = clearHalt(outEndpoint.address)
        return ClaimResult(true, "claimed; setInterface=$alt clearHalt(in)=$c1 clearHalt(out)=$c2")
    }

    data class ClaimResult(val ok: Boolean, val detail: String)

    private fun clearHalt(address: Int): Int =
        // CLEAR_FEATURE(ENDPOINT_HALT): bmRequestType=0x02, bRequest=0x01, wValue=0, wIndex=endpoint
        connection.controlTransfer(0x02, 0x01, 0, address, null, 0, 1000)

    override fun write(packet: ByteArray) {
        if (closed) return
        synchronized(writeLock) {
            var off = 0
            while (off < packet.size) {
                val chunk = minOf(packet.size - off, MAX_SYNC_TRANSFER)
                val n = connection.bulkTransfer(outEndpoint, packet, off, chunk, WRITE_TIMEOUT_MS)
                if (n < 0) {
                    writeErrors++
                    throw IOException("bulk OUT failed (${packet.size} bytes)")
                }
                off += n
            }
            bytesWritten += packet.size
        }
    }

    /** 実際に使われている受信方式（診断表示用） */
    @Volatile var rxMode: String = "-"; private set

    /**
     * 受信ループ（呼び出しスレッドをブロックする）。[stop] が呼ばれるか USB エラーで終了。
     * [onData] には受信したバイト列を渡す（呼び出し中のみ有効）。
     *
     * 端末の USB スタックによっては非同期要求（UsbRequest）が使えない／大きなバッファを受け付けないため、
     * 次の順にフォールバックする: UsbRequest 64KiB×8 → 16KiB×8 → 16KiB×1 → 同期 bulkTransfer。
     */
    fun readLoop(
        isDevicePresent: () -> Boolean,
        log: (String) -> Unit,
        onData: (ByteArray, Int, Int) -> Unit,
    ) {
        running.set(true)
        try {
            val attempts = buildList {
                if (Build.VERSION.SDK_INT >= 28) add(64 * 1024 to IN_FLIGHT) // API 27 以前は 16KiB 上限
                add(16 * 1024 to IN_FLIGHT)
                add(16 * 1024 to 1)
            }
            val failed = ArrayList<String>()
            for ((size, inFlight) in attempts) {
                if (!running.get()) return
                val label = "UsbRequest ${size / 1024}KiB×$inFlight"
                when (val r = runAsync(size, inFlight, label, log, onData)) {
                    null -> return // 正常終了（stop）
                    else -> { failed += "$label: $r"; log("rx: $label unavailable ($r)") }
                }
            }
            rxMode = "bulkTransfer ${SYNC_READ_SIZE / 1024}KiB (同期)"
            log("rx mode: $rxMode; async failed: ${failed.joinToString()}")
            runSync(isDevicePresent, onData)
        } finally {
            running.set(false)
        }
    }

    /**
     * 非同期受信。初回のキューイングに失敗した場合は後片付けして失敗理由を返す（フォールバック用）。
     * 受信開始後のエラーは例外を投げる。stop で終了した場合は null。
     */
    private fun runAsync(size: Int, inFlight: Int, label: String, log: (String) -> Unit, onData: (ByteArray, Int, Int) -> Unit): String? {
        val requests = ArrayList<UsbRequest>(inFlight)
        val tmp = ByteArray(size)
        try {
            for (i in 0 until inFlight) {
                val r = UsbRequest()
                if (!r.initialize(connection, inEndpoint)) { r.close(); return "initialize failed" }
                val b = ByteBuffer.allocateDirect(size)
                r.clientData = b
                requests += r
                if (!r.queue(b)) return "queue failed (#${i + 1})"
            }
            rxMode = label
            log("rx mode: $label")
            while (running.get()) {
                val r = try {
                    connection.requestWait(READ_POLL_MS)
                } catch (e: TimeoutException) {
                    continue
                } ?: throw IOException("requestWait returned null (device disconnected?)")
                val b = r.clientData as? ByteBuffer ?: continue
                val n = b.position()
                b.flip()
                b.get(tmp, 0, n)
                b.clear()
                if (running.get() && !r.queue(b)) throw IOException("UsbRequest.queue failed while streaming (device disconnected?)")
                if (n > 0) {
                    bytesRead += n
                    onData(tmp, 0, n)
                }
            }
            return null
        } finally {
            for (r in requests) { runCatching { r.cancel() } }
            for (r in requests) { runCatching { r.close() } }
        }
    }

    /**
     * 同期 bulkTransfer による受信（最も互換性が高い）。
     * bulkTransfer はタイムアウトとエラーを区別できないため、失敗時はデバイスの存在で判定する。
     */
    private fun runSync(isDevicePresent: () -> Boolean, onData: (ByteArray, Int, Int) -> Unit) {
        val buf = ByteArray(SYNC_READ_SIZE)
        var failures = 0
        while (running.get()) {
            val n = connection.bulkTransfer(inEndpoint, buf, buf.size, SYNC_READ_TIMEOUT_MS)
            if (n > 0) {
                failures = 0
                bytesRead += n
                onData(buf, 0, n)
            } else if (n < 0) {
                if (!running.get()) break
                failures++
                // タイムアウト（データ無し）か切断か: 数回連続したらデバイスの存在を確認
                if (failures >= 3 && !isDevicePresent()) throw IOException("bulkTransfer failed (device disconnected)")
            }
        }
    }

    fun stop() = running.set(false)

    fun close() {
        stop()
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
    }

    companion object {
        const val IN_FLIGHT = 8
        const val READ_POLL_MS = 250L
        const val WRITE_TIMEOUT_MS = 1000
        const val MAX_SYNC_TRANSFER = 16 * 1024
        const val SYNC_READ_SIZE = 16 * 1024
        const val SYNC_READ_TIMEOUT_MS = 500

        fun findBulk(iface: UsbInterface, dirIn: Boolean): UsbEndpoint? =
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    (it.direction == UsbConstants.USB_DIR_IN) == dirIn
            }
    }
}

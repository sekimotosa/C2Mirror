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

    /**
     * 受信ループ（呼び出しスレッドをブロックする）。[stop] が呼ばれるか USB エラーで終了。
     * [onData] には受信したバイト列を渡す（呼び出し中のみ有効）。
     */
    fun readLoop(onData: (ByteArray, Int, Int) -> Unit) {
        running.set(true)
        val size = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024 // API 27 以前は 16KB 上限
        val requests = ArrayList<UsbRequest>(IN_FLIGHT)
        val tmp = ByteArray(size)
        try {
            repeat(IN_FLIGHT) {
                val r = UsbRequest()
                if (!r.initialize(connection, inEndpoint)) throw IOException("UsbRequest.initialize failed")
                val b = ByteBuffer.allocateDirect(size)
                r.clientData = b
                requests += r
                if (!r.queue(b)) throw IOException("UsbRequest.queue failed")
            }
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
                if (running.get() && !r.queue(b)) throw IOException("UsbRequest.queue failed (device disconnected?)")
                if (n > 0) {
                    bytesRead += n
                    onData(tmp, 0, n)
                }
            }
        } finally {
            running.set(false)
            for (r in requests) { runCatching { r.cancel() } }
            for (r in requests) { runCatching { r.close() } }
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

        fun findBulk(iface: UsbInterface, dirIn: Boolean): UsbEndpoint? =
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    (it.direction == UsbConstants.USB_DIR_IN) == dirIn
            }
    }
}

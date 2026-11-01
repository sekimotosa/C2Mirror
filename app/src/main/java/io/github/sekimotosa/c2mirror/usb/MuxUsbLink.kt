package io.github.sekimotosa.c2mirror.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * usbmux インターフェース（Class 0xFF / Subclass 0xFE）の Bulk 送受信。
 * AV 側が UsbRequest/requestWait を使うため、こちらは同期 bulkTransfer のみを使い干渉させない。
 */
class MuxUsbLink(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val writeLock = Any()

    fun claim(): Boolean = connection.claimInterface(iface, true)

    @Volatile private var closed = false

    fun write(data: ByteArray) {
        if (closed) throw IOException("usbmux link closed")
        synchronized(writeLock) {
            if (closed) throw IOException("usbmux link closed")
            var off = 0
            while (off < data.size) {
                val n = connection.bulkTransfer(outEp, data, off, minOf(16384, data.size - off), 1000)
                if (n < 0) throw IOException("usbmux bulk OUT failed")
                off += n
            }
            // 転送長が maxPacketSize の倍数なら ZLP で区切る（usbmuxd と同じ扱い）
            if (data.isNotEmpty() && data.size % outEp.maxPacketSize == 0) {
                connection.bulkTransfer(outEp, ByteArray(0), 0, 1000)
            }
        }
    }

    fun start(onData: (ByteArray, Int, Int) -> Unit, onError: (Throwable) -> Unit) {
        running.set(true)
        thread = Thread({
            val buf = ByteArray(16384)
            try {
                while (running.get()) {
                    val n = connection.bulkTransfer(inEp, buf, buf.size, 300)
                    if (n > 0) onData(buf, 0, n)
                }
            } catch (e: Throwable) {
                if (running.get()) onError(e)
            }
        }, "c2m-mux-rx").apply { start() }
    }

    fun close() {
        running.set(false)
        thread?.join(1000)
        thread = null
        synchronized(writeLock) { closed = true } // 書き込み中なら完了を待つ
        runCatching { connection.releaseInterface(iface) }
    }
}

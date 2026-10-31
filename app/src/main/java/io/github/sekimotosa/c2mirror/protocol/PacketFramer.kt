package io.github.sekimotosa.c2mirror.protocol

/**
 * USB Bulk IN から届くバイト列を QuickTime パケット単位に切り出すストリームパーサー。
 *
 * - 1回の USB 読み込みが1パケットと一致するとは限らない（分割・連結の両方に対応）。
 * - 内部バッファが空のときは受信バッファ上で直接切り出し、コピーを発生させない（低遅延）。
 * - 異常な Length を検出した場合は既知マジックを探して再同期を試み、
 *   再同期で捨てたバイト数が上限を超えたら [ProtocolException] を投げる。
 *
 * [onPacket] には長さフィールドを除いた本体 (buf, off, len) が渡される。コールバック中のみ有効。
 */
class PacketFramer(
    private val maxPacketSize: Int = DEFAULT_MAX_PACKET,
    private val maxResyncBytes: Int = 4 * 1024 * 1024,
    private val onPacket: (buf: ByteArray, off: Int, len: Int) -> Unit,
) {
    private var buf = ByteArray(256 * 1024)
    private var r = 0
    private var w = 0

    var packetsDelivered = 0L
        private set
    var bytesDiscarded = 0L
        private set
    var resyncCount = 0
        private set
    private var discardedSinceSync = 0L

    val buffered get() = w - r

    fun feed(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (w == r) {
            // ゼロコピー経路: src 上で完結するパケットを直接処理
            r = 0; w = 0
            val consumed = drain(src, off, off + len)
            val rest = len - consumed
            if (rest > 0) append(src, off + consumed, rest)
        } else {
            append(src, off, len)
            val consumed = drain(buf, r, w)
            r += consumed
            if (r == w) { r = 0; w = 0 }
        }
        reserveForPending()
    }

    /** 受信途中の大きなパケット（IDR フレーム等）のために必要量を一度に確保し、再コピー回数を減らす。 */
    private fun reserveForPending() {
        if (w - r < 4) return
        val l = buf.u32le(r)
        if (l in 8..maxPacketSize.toLong()) ensureCapacity(l.toInt())
    }

    fun reset() { r = 0; w = 0; discardedSinceSync = 0 }

    /** [start, end) から取り出せるだけパケットを取り出し、消費したバイト数を返す。 */
    private fun drain(a: ByteArray, start: Int, end: Int): Int {
        var p = start
        while (end - p >= 4) {
            val length = a.u32le(p)
            if (!isPlausible(a, p, end, length)) {
                val next = findResync(a, p + 1, end)
                val skip = (if (next < 0) maxOf(end - p - 7, 1) else next - p)
                // 再同期点が見つからない場合でも末尾の数バイトは次の受信と結合して判定するため残す
                p += skip
                bytesDiscarded += skip; discardedSinceSync += skip
                if (next >= 0) resyncCount++
                if (discardedSinceSync > maxResyncBytes) {
                    throw ProtocolException("stream desynchronized: discarded $discardedSinceSync bytes")
                }
                continue
            }
            val l = length.toInt()
            if (end - p < l) break // 不完全: 続きを待つ
            discardedSinceSync = 0
            packetsDelivered++
            onPacket(a, p + 4, l - 4)
            p += l
        }
        return p - start
    }

    private fun isPlausible(a: ByteArray, p: Int, end: Int, length: Long): Boolean {
        if (length < 8 || length > maxPacketSize) return false
        if (end - p >= 8) {
            val type = a.i32le(p + 4)
            if (type != Magic.SYNC && type != Magic.ASYN && type != Magic.PING) return false
        }
        return true
    }

    private fun findResync(a: ByteArray, from: Int, end: Int): Int {
        var q = from
        while (q + 8 <= end) {
            val type = a.i32le(q + 4)
            if (type == Magic.SYNC || type == Magic.ASYN || type == Magic.PING) {
                val l = a.u32le(q)
                if (l in 8..maxPacketSize.toLong()) return q
            }
            q++
        }
        return -1
    }

    private fun ensureCapacity(total: Int) {
        if (r > 0 && buf.size - r < total) compact()
        if (buf.size - r < total) {
            val n = ByteArray(maxOf(buf.size * 2, Integer.highestOneBit(total - 1) shl 1))
            System.arraycopy(buf, r, n, 0, w - r)
            w -= r; r = 0; buf = n
        }
    }

    private fun compact() {
        if (r == 0) return
        System.arraycopy(buf, r, buf, 0, w - r)
        w -= r; r = 0
    }

    private fun append(src: ByteArray, off: Int, len: Int) {
        if (buf.size - w < len) {
            compact()
            if (buf.size - w < len) buf = buf.copyOf(maxOf(buf.size * 2, w + len))
        }
        System.arraycopy(src, off, buf, w, len)
        w += len
    }

    companion object {
        const val DEFAULT_MAX_PACKET = 16 * 1024 * 1024
    }
}

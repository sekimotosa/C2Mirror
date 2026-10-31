/*
 * C2Mirror - QuickTime USB AV protocol (Kotlin port).
 * Portions derived from quicktime_video_hack (https://github.com/danielpaulus/quicktime_video_hack),
 * Copyright (c) 2019 Daniel Paulus, MIT License. See THIRD_PARTY_NOTICES.md.
 */
package io.github.sekimotosa.c2mirror.protocol

/** プロトコル違反・破損データを表す例外。 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 全ての値はリトルエンディアン（参考実装 encoding/binary.LittleEndian に一致）。 */
internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

internal fun ByteArray.u16le(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

internal fun ByteArray.i32le(o: Int): Int =
    u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

internal fun ByteArray.u32le(o: Int): Long = i32le(o).toLong() and 0xFFFFFFFFL

internal fun ByteArray.u64le(o: Int): Long =
    (i32le(o).toLong() and 0xFFFFFFFFL) or (i32le(o + 4).toLong() shl 32)

internal fun ByteArray.u16be(o: Int): Int = (u8(o) shl 8) or u8(o + 1)

internal fun ByteArray.i32be(o: Int): Int =
    (u8(o) shl 24) or (u8(o + 1) shl 16) or (u8(o + 2) shl 8) or u8(o + 3)

internal fun ByteArray.putI32le(o: Int, v: Int) {
    this[o] = v.toByte()
    this[o + 1] = (v ushr 8).toByte()
    this[o + 2] = (v ushr 16).toByte()
    this[o + 3] = (v ushr 24).toByte()
}

internal fun ByteArray.putI64le(o: Int, v: Long) {
    putI32le(o, v.toInt())
    putI32le(o + 4, (v ushr 32).toInt())
}

/** 4文字コード。参考実装の uint32 定数（例: SYNC = 0x73796E63）と同じ値を返す。 */
fun fourCC(s: String): Int {
    require(s.length == 4)
    return (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
}

/** ログ・診断用に4文字コードを読みやすく表示する（ワイヤ上はリトルエンディアンのため逆順になる）。 */
fun fourCCToString(v: Int): String {
    val chars = charArrayOf(
        ((v ushr 24) and 0xFF).toChar(), ((v ushr 16) and 0xFF).toChar(),
        ((v ushr 8) and 0xFF).toChar(), (v and 0xFF).toChar()
    )
    return if (chars.all { it.code in 0x20..0x7E }) String(chars) else "0x%08x".format(v)
}

/** 可変長のリトルエンディアン書き込みバッファ。 */
internal class ByteWriter(initial: Int = 256) {
    var buf = ByteArray(initial)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun i32(v: Int) { ensure(4); buf.putI32le(size, v); size += 4 }
    fun i64(v: Long) { ensure(8); buf.putI64le(size, v); size += 8 }
    fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) {
        ensure(len); System.arraycopy(b, off, buf, size, len); size += len
    }
    fun patchI32(at: Int, v: Int) = buf.putI32le(at, v)
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * [off, end) の範囲にある「4byte長 + 4byteマジック」形式のアトムを検証し、長さを返す。
 * 参考実装 common.ParseLengthAndMagic 相当だが、範囲外アクセスを起こさないよう厳密に検査する。
 */
internal fun checkAtom(buf: ByteArray, off: Int, end: Int, expectedMagic: Int): Int {
    if (off < 0 || end - off < 8) throw ProtocolException("atom header truncated (need 8, have ${end - off})")
    val len = buf.u32le(off)
    if (len < 8 || len > (end - off)) {
        throw ProtocolException("invalid atom length $len (available ${end - off}) for ${fourCCToString(buf.i32le(off + 4))}")
    }
    val magic = buf.i32le(off + 4)
    if (magic != expectedMagic) {
        throw ProtocolException("unexpected magic ${fourCCToString(magic)}, expected ${fourCCToString(expectedMagic)}")
    }
    return len.toInt()
}

/** 境界チェック付きのアトム長取得（マジックは問わない）。 */
internal fun atomLength(buf: ByteArray, off: Int, end: Int): Int {
    if (end - off < 8) throw ProtocolException("atom header truncated")
    val len = buf.u32le(off)
    if (len < 8 || len > (end - off)) throw ProtocolException("invalid atom length $len (available ${end - off})")
    return len.toInt()
}

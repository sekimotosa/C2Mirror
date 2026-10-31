package io.github.sekimotosa.c2mirror

/**
 * テストデータは quicktime_video_hack (MIT License, Copyright (c) 2019 danielpaulus) の
 * 実機キャプチャ fixtures をそのまま使用している（src/test/resources/fixtures）。
 */
object Fixtures {
    fun load(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    /** 先頭の4バイト長が付いていれば取り除き、パケット本体を返す（fixtures には両方の形式がある）。 */
    fun body(name: String): ByteArray {
        val d = load(name)
        val len = (d[0].toLong() and 0xFF) or ((d[1].toLong() and 0xFF) shl 8) or ((d[2].toLong() and 0xFF) shl 16) or ((d[3].toLong() and 0xFF) shl 24)
        return if (len == d.size.toLong()) d.copyOfRange(4, d.size) else d
    }

    /** 4バイト長付きのワイヤ形式 */
    fun wire(name: String): ByteArray {
        val b = body(name)
        val out = ByteArray(b.size + 4)
        val l = out.size
        out[0] = l.toByte(); out[1] = (l shr 8).toByte(); out[2] = (l shr 16).toByte(); out[3] = (l shr 24).toByte()
        System.arraycopy(b, 0, out, 4, b.size)
        return out
    }

    fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/*
 * CMFormatDescription (fdsc) の解析。
 * Portions derived from quicktime_video_hack (MIT License, Copyright (c) 2019 Daniel Paulus).
 */
package io.github.sekimotosa.c2mirror.protocol

class FormatDescription(
    val mediaType: Int,
    val width: Int = 0,
    val height: Int = 0,
    val codec: Int = 0,
    val extensions: IndexKeyDict? = null,
    val asbd: AudioStreamBasicDescription? = null,
) {
    val isVideo get() = mediaType == Magic.MEDIA_VIDEO

    /**
     * Extensions[49] (SampleDescriptionExtensionAtoms) の [105] に avcC / hvcC 等の
     * デコーダ設定レコードがバイト列で入っている（参考実装 extractPPS 参照）。
     * キー番号が変わった場合に備え、見つからなければ 49 配下の最初のバイト列を返す。
     */
    fun decoderConfigurationRecord(): ByteArray? {
        val atoms = extensions?.get(49) as? IndexKeyDict ?: return null
        (atoms[105] as? ByteArray)?.let { return it }
        return atoms.entries.firstNotNullOfOrNull { it.second as? ByteArray }
    }

    override fun toString(): String =
        if (isVideo) "fdsc{video ${width}x$height codec=${fourCCToString(codec)}}"
        else "fdsc{${fourCCToString(mediaType)} asbd=$asbd}"

    companion object {
        fun parse(buf: ByteArray, off: Int, end: Int): FormatDescription {
            val len = checkAtom(buf, off, end, Magic.FDSC)
            val fEnd = off + len
            var p = off + 8
            val mLen = checkAtom(buf, p, fEnd, Magic.MDIA)
            if (mLen != 12) throw ProtocolException("invalid mdia length $mLen")
            val mediaType = buf.i32le(p + 8)
            p += mLen

            if (mediaType == Magic.MEDIA_SOUND) {
                val aLen = checkAtom(buf, p, fEnd, Magic.ASBD)
                return FormatDescription(mediaType, asbd = AudioStreamBasicDescription.parse(buf, p + 8, p + aLen))
            }

            var width = 0
            var height = 0
            var codec = 0
            var extensions: IndexKeyDict? = null
            // 既知アトムを順不同で読み、未知のアトムは長さでスキップする（新しい iOS への耐性）。
            while (p < fEnd) {
                val aLen = atomLength(buf, p, fEnd)
                when (buf.i32le(p + 4)) {
                    Magic.VDIM -> {
                        if (aLen != 16) throw ProtocolException("invalid vdim length $aLen")
                        width = buf.i32le(p + 8); height = buf.i32le(p + 12)
                    }
                    Magic.CODC -> {
                        if (aLen != 12) throw ProtocolException("invalid codc length $aLen")
                        codec = buf.i32le(p + 8)
                    }
                    Magic.EXTN -> extensions = Dicts.parseIndexDict(buf, p, p + aLen, Magic.EXTN)
                }
                p += aLen
            }
            return FormatDescription(mediaType, width, height, codec, extensions)
        }
    }
}

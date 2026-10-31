/*
 * CMSampleBuffer (sbuf) の解析。映像データ(sdat)はコピーせず元バッファへの参照として保持する。
 * Portions derived from quicktime_video_hack (MIT License, Copyright (c) 2019 Daniel Paulus).
 */
package io.github.sekimotosa.c2mirror.protocol

data class CMSampleTimingInfo(val duration: CMTime, val presentationTimeStamp: CMTime, val decodeTimeStamp: CMTime)

class CMSampleBuffer(
    val mediaType: Int,
    /** sdat を含む元バッファ。パケット処理コールバックの間だけ有効。 */
    val buffer: ByteArray,
) {
    var outputPresentationTimestamp: CMTime? = null
    var formatDescription: FormatDescription? = null
    var numSamples: Int = 0
    var sampleTimingInfo: List<CMSampleTimingInfo> = emptyList()
    var sampleSizes: IntArray = IntArray(0)
    var sampleDataOffset: Int = -1
    var sampleDataLength: Int = 0
    var attachmentsRange: IntRange? = null
    var saryRange: IntRange? = null
    val unknownAtoms = ArrayList<Int>(0)

    val hasSampleData get() = sampleDataOffset >= 0
    val hasFormatDescription get() = formatDescription != null

    fun sampleData(): ByteArray = if (hasSampleData) buffer.copyOfRange(sampleDataOffset, sampleDataOffset + sampleDataLength) else ByteArray(0)

    fun attachments(): IndexKeyDict? = attachmentsRange?.let { Dicts.parseIndexDict(buffer, it.first, it.last + 1, Magic.SATT) }

    fun sary(): IndexKeyDict? = saryRange?.let { r ->
        // sary アトムの中に dict アトムが入っている
        Dicts.parseIndexDict(buffer, r.first + 8, r.last + 1)
    }

    companion object {
        private const val TIMING_INFO_LENGTH = 3 * CMTime.LENGTH

        /** [off, end) は sbuf アトムの先頭から。 */
        fun parse(buf: ByteArray, off: Int, end: Int, mediaType: Int): CMSampleBuffer {
            val len = checkAtom(buf, off, end, Magic.SBUF)
            val sEnd = off + len
            val sb = CMSampleBuffer(mediaType, buf)
            var p = off + 8
            while (p < sEnd) {
                val aLen = atomLength(buf, p, sEnd)
                val aEnd = p + aLen
                when (val magic = buf.i32le(p + 4)) {
                    Magic.OPTS -> sb.outputPresentationTimestamp = CMTime.parse(buf, p + 8, aEnd)
                    Magic.STIA -> {
                        val body = aLen - 8
                        if (body % TIMING_INFO_LENGTH != 0) throw ProtocolException("invalid stia length $aLen")
                        sb.sampleTimingInfo = List(body / TIMING_INFO_LENGTH) { i ->
                            val b = p + 8 + i * TIMING_INFO_LENGTH
                            CMSampleTimingInfo(CMTime.parse(buf, b), CMTime.parse(buf, b + 24), CMTime.parse(buf, b + 48))
                        }
                    }
                    Magic.SDAT -> { sb.sampleDataOffset = p + 8; sb.sampleDataLength = aLen - 8 }
                    Magic.NSMP -> {
                        if (aLen != 12) throw ProtocolException("invalid nsmp length $aLen")
                        sb.numSamples = buf.i32le(p + 8)
                    }
                    Magic.SSIZ -> {
                        val body = aLen - 8
                        if (body % 4 != 0) throw ProtocolException("invalid ssiz length $aLen")
                        sb.sampleSizes = IntArray(body / 4) { buf.i32le(p + 8 + it * 4) }
                    }
                    Magic.FDSC -> sb.formatDescription = FormatDescription.parse(buf, p, aEnd)
                    Magic.SATT -> sb.attachmentsRange = p until aEnd
                    Magic.SARY -> sb.saryRange = p until aEnd
                    else -> sb.unknownAtoms += magic // 参考実装はエラーにするが、ここではスキップして継続する
                }
                p = aEnd
            }
            return sb
        }
    }
}

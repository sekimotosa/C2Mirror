package io.github.sekimotosa.c2mirror.video

/** RBSP <-> NAL のエミュレーション防止バイト(0x03)処理。 */
object Rbsp {
    fun unescape(nal: ByteArray, off: Int = 0, len: Int = nal.size - off): ByteArray {
        val out = java.io.ByteArrayOutputStream(len)
        var zeros = 0
        for (i in off until off + len) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    fun escape(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (x in rbsp) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) { out.write(3); zeros = 0 }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}

class BitReader(private val data: ByteArray) {
    var pos = 0 // ビット位置
        private set
    val bitsLeft get() = data.size * 8 - pos

    fun bit(): Int {
        if (pos >= data.size * 8) throw IllegalStateException("bitstream exhausted")
        val v = (data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
        pos++
        return v
    }

    fun bits(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or bit().toLong() }; return v }
    fun flag() = bit() == 1
    fun ue(): Long {
        var zeros = 0
        while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException("invalid exp-golomb") }
        return ((1L shl zeros) - 1) + bits(zeros)
    }
    fun se(): Long { val k = ue(); return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2) }
}

class BitWriter {
    private val out = java.io.ByteArrayOutputStream()
    private var cur = 0
    private var n = 0

    fun bit(b: Int) {
        cur = (cur shl 1) or (b and 1); n++
        if (n == 8) { out.write(cur); cur = 0; n = 0 }
    }
    fun bits(v: Long, count: Int) { for (i in count - 1 downTo 0) bit(((v shr i) and 1).toInt()) }
    fun flag(f: Boolean) = bit(if (f) 1 else 0)
    fun ue(v: Long) {
        val x = v + 1
        val len = 64 - java.lang.Long.numberOfLeadingZeros(x)
        bits(0, len - 1); bits(x, len)
    }
    /** rbsp_trailing_bits */
    fun trailing() { bit(1); while (n != 0) bit(0) }
    fun toByteArray(): ByteArray { check(n == 0); return out.toByteArray() }
}

/** H.264 SPS の解析結果（遅延最適化に必要な範囲）。 */
data class H264Sps(
    val profileIdc: Int,
    val levelIdc: Int,
    val width: Int,
    val height: Int,
    val maxNumRefFrames: Int,
    val vuiPresent: Boolean,
    val bitstreamRestriction: Boolean,
    val maxNumReorderFrames: Int?,
    val maxDecFrameBuffering: Int?,
    /** RBSP 内で vui_parameters_present_flag が置かれているビット位置 */
    internal val vuiFlagBitPos: Int,
    /** RBSP 内で bitstream_restriction_flag が置かれているビット位置（VUI が無ければ -1） */
    internal val restrictionFlagBitPos: Int,
    internal val restrictionFields: LongArray?, // mv_over_pic, max_bytes, max_bits, log2_h, log2_v
)

object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** nal はヘッダバイトを含む NAL ユニット（エスケープ済み）。 */
    fun parseSps(nal: ByteArray): H264Sps {
        require(nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == NAL_SPS) { "not an SPS" }
        val rbsp = Rbsp.unescape(nal, 1, nal.size - 1)
        val r = BitReader(rbsp)
        val profile = r.bits(8).toInt()
        r.bits(8) // constraint flags
        val level = r.bits(8).toInt()
        r.ue() // sps id
        var chromaFormatIdc = 1L
        var separateColourPlane = false
        if (profile in HIGH_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3L) separateColourPlane = r.flag()
            r.ue(); r.ue() // bit depth
            r.flag() // qpprime_y_zero_transform_bypass
            if (r.flag()) { // seq_scaling_matrix_present
                val count = if (chromaFormatIdc != 3L) 8 else 12
                for (i in 0 until count) if (r.flag()) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0L -> r.ue()
            1L -> { r.flag(); r.se(); r.se(); val n = r.ue(); repeat(n.toInt()) { r.se() } }
        }
        val maxNumRefFrames = r.ue().toInt()
        r.flag() // gaps
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.flag()
        if (!frameMbsOnly) r.flag()
        r.flag() // direct_8x8
        var cropL = 0L; var cropR = 0L; var cropT = 0L; var cropB = 0L
        if (r.flag()) { cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue() }
        val chromaArrayType = if (separateColourPlane) 0L else chromaFormatIdc
        val cropUnitX = if (chromaArrayType == 0L) 1 else if (chromaFormatIdc == 3L) 1 else 2
        val cropUnitY = (if (chromaArrayType == 0L) 1 else if (chromaFormatIdc == 1L) 2 else 1) * (if (frameMbsOnly) 1 else 2)
        val width = (wMbs * 16 - cropUnitX * (cropL + cropR)).toInt()
        val height = (hMapUnits * 16 * (if (frameMbsOnly) 1 else 2) - cropUnitY * (cropT + cropB)).toInt()

        val vuiFlagPos = r.pos
        val vui = r.flag()
        var restrictionPos = -1
        var restriction = false
        var reorder: Int? = null
        var decBuf: Int? = null
        var fields: LongArray? = null
        if (vui) {
            if (r.flag()) { if (r.bits(8) == 255L) { r.bits(16); r.bits(16) } } // aspect_ratio
            if (r.flag()) r.flag() // overscan
            if (r.flag()) { r.bits(3); r.flag(); if (r.flag()) { r.bits(8); r.bits(8); r.bits(8) } } // video_signal_type
            if (r.flag()) { r.ue(); r.ue() } // chroma_loc
            if (r.flag()) { r.bits(32); r.bits(32); r.flag() } // timing_info
            val nalHrd = r.flag(); if (nalHrd) skipHrd(r)
            val vclHrd = r.flag(); if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.flag() // low_delay_hrd
            r.flag() // pic_struct_present
            restrictionPos = r.pos
            restriction = r.flag()
            if (restriction) {
                fields = longArrayOf(r.bits(1), r.ue(), r.ue(), r.ue(), r.ue())
                reorder = r.ue().toInt()
                decBuf = r.ue().toInt()
            }
        }
        return H264Sps(profile, level, width, height, maxNumRefFrames, vui, restriction, reorder, decBuf,
            vuiFlagPos, restrictionPos, fields)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8L; var next = 8L
        for (j in 0 until size) {
            if (next != 0L) next = (last + r.se() + 256) % 256
            last = if (next == 0L) last else next
        }
    }

    private fun skipHrd(r: BitReader) {
        val cpbCnt = r.ue() + 1
        r.bits(4); r.bits(4)
        repeat(cpbCnt.toInt()) { r.ue(); r.ue(); r.flag() }
        r.bits(5); r.bits(5); r.bits(5); r.bits(5)
    }

    /**
     * 低遅延化のための SPS 書き換え。
     * VUI の bitstream_restriction に max_num_reorder_frames = 0 を設定し、デコーダが
     * 表示順並べ替えのためにフレームを溜め込む（＝表示遅延が増える）のを防ぐ。
     * iOS の画面ミラーリングは B フレームを使わない前提だが、実機の SPS で要確認（診断に表示）。
     * 書き換え不要・失敗時は null を返す（呼び出し側は元の SPS を使う）。
     */
    fun rewriteSpsForLowLatency(nal: ByteArray): ByteArray? {
        val sps = try { parseSps(nal) } catch (e: Exception) { return null }
        if (sps.bitstreamRestriction && sps.maxNumReorderFrames == 0) return null
        val rbsp = Rbsp.unescape(nal, 1, nal.size - 1)
        val r = BitReader(rbsp)
        val w = BitWriter()
        val copyUntil = if (sps.vuiPresent) sps.restrictionFlagBitPos else sps.vuiFlagBitPos
        repeat(copyUntil) { w.bit(r.bit()) }
        if (!sps.vuiPresent) {
            w.flag(true) // vui_parameters_present_flag
            // aspect, overscan, video_signal, chroma_loc, timing, nal_hrd, vcl_hrd, pic_struct = 0
            repeat(8) { w.flag(false) }
        }
        val f = sps.restrictionFields ?: longArrayOf(1, 2, 1, 16, 16)
        val dec = maxOf(1, sps.maxNumRefFrames)
        w.flag(true) // bitstream_restriction_flag
        w.bits(f[0], 1); w.ue(f[1]); w.ue(f[2]); w.ue(f[3]); w.ue(f[4])
        w.ue(0) // max_num_reorder_frames
        w.ue(dec.toLong()) // max_dec_frame_buffering
        w.trailing()
        val body = Rbsp.escape(w.toByteArray())
        return byteArrayOf(nal[0]) + body
    }
}

/** avcC (AVCDecoderConfigurationRecord) */
class AvcDecoderConfig(val nalLengthSize: Int, val sps: List<ByteArray>, val pps: List<ByteArray>) {
    companion object {
        fun parse(d: ByteArray): AvcDecoderConfig {
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            require(d.size >= 7 && u8(0) == 1) { "invalid avcC" }
            val lengthSize = (u8(4) and 0x03) + 1
            var p = 5
            val sps = ArrayList<ByteArray>()
            repeat(u8(p) and 0x1F) {
                val l = u16(p + 1); require(p + 3 + l <= d.size) { "avcC SPS truncated" }
                sps += d.copyOfRange(p + 3, p + 3 + l); p += 2 + l
            }
            p += 1
            require(p < d.size) { "avcC PPS count missing" }
            val pps = ArrayList<ByteArray>()
            val ppsCount = u8(p)
            p += 1
            repeat(ppsCount) {
                val l = u16(p); require(p + 2 + l <= d.size) { "avcC PPS truncated" }
                pps += d.copyOfRange(p + 2, p + 2 + l); p += 2 + l
            }
            require(sps.isNotEmpty() && pps.isNotEmpty()) { "avcC without SPS/PPS" }
            return AvcDecoderConfig(lengthSize, sps, pps)
        }
    }
}

/** hvcC (HEVCDecoderConfigurationRecord)。iOS が HEVC を送ってきた場合用（実機未検証）。 */
class HevcDecoderConfig(val nalLengthSize: Int, val parameterSets: List<ByteArray>) {
    companion object {
        fun parse(d: ByteArray): HevcDecoderConfig {
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            require(d.size >= 23 && u8(0) == 1) { "invalid hvcC" }
            val lengthSize = (u8(21) and 0x03) + 1
            val arrays = u8(22)
            var p = 23
            val sets = ArrayList<ByteArray>()
            repeat(arrays) {
                require(p + 3 <= d.size) { "hvcC truncated" }
                val n = u16(p + 1); p += 3
                repeat(n) {
                    val l = u16(p); require(p + 2 + l <= d.size) { "hvcC NAL truncated" }
                    sets += d.copyOfRange(p + 2, p + 2 + l); p += 2 + l
                }
            }
            require(sets.isNotEmpty()) { "hvcC without parameter sets" }
            return HevcDecoderConfig(lengthSize, sets)
        }
    }
}

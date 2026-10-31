package io.github.sekimotosa.c2mirror.video

import io.github.sekimotosa.c2mirror.protocol.CMSampleBuffer
import io.github.sekimotosa.c2mirror.protocol.FormatDescription
import io.github.sekimotosa.c2mirror.protocol.Magic
import io.github.sekimotosa.c2mirror.protocol.fourCCToString
import java.nio.ByteBuffer

/** デコーダ設定（MediaCodec の csd とサイズ）。 */
class VideoFormat(
    val mime: String,
    val codecTag: String,
    val width: Int,
    val height: Int,
    /** Annex-B（スタートコード付き）の csd-0, csd-1 ... */
    val csd: List<ByteArray>,
    val nalLengthSize: Int,
    val sps: H264Sps? = null,
    val spsRewritten: Boolean = false,
) {
    fun isSameStream(o: VideoFormat?): Boolean =
        o != null && mime == o.mime && width == o.width && height == o.height && nalLengthSize == o.nalLengthSize &&
            csd.size == o.csd.size && csd.indices.all { csd[it].contentEquals(o.csd[it]) }

    override fun toString(): String {
        val s = sps
        val spsInfo = if (s != null) ", profile=${s.profileIdc} level=${s.levelIdc} refs=${s.maxNumRefFrames}" +
            " reorder=${s.maxNumReorderFrames ?: "n/a"} spsRewritten=$spsRewritten" else ""
        return "$codecTag ${width}x$height nalLen=$nalLengthSize$spsInfo"
    }

    companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun fromFormatDescription(fd: FormatDescription, lowLatencySps: Boolean = true): VideoFormat {
            require(fd.isVideo) { "not a video format description" }
            val record = fd.decoderConfigurationRecord() ?: throw IllegalArgumentException("fdsc without decoder configuration record")
            return when (fd.codec) {
                Magic.CODEC_HVC1, Magic.CODEC_HEV1 -> {
                    val cfg = HevcDecoderConfig.parse(record)
                    VideoFormat(MIME_HEVC, fourCCToString(fd.codec), fd.width, fd.height,
                        listOf(cfg.parameterSets.fold(ByteArray(0)) { acc, n -> acc + START_CODE + n }), cfg.nalLengthSize)
                }
                else -> {
                    // avc1 もしくは不明なコーデックは H.264 として扱う（参考実装は avc1 のみ観測）
                    val cfg = AvcDecoderConfig.parse(record)
                    val original = cfg.sps.first()
                    val rewritten = if (lowLatencySps) H264.rewriteSpsForLowLatency(original) else null
                    val sps = rewritten ?: original
                    val info = runCatching { H264.parseSps(original) }.getOrNull()
                    val w = if (fd.width > 0) fd.width else info?.width ?: 0
                    val h = if (fd.height > 0) fd.height else info?.height ?: 0
                    VideoFormat(MIME_AVC, fourCCToString(fd.codec), w, h,
                        listOf(START_CODE + sps, START_CODE + cfg.pps.first()), cfg.nalLengthSize, info, rewritten != null)
                }
            }
        }
    }
}

/** 長さプレフィクス形式(AVCC/HVCC)の NAL 列を Annex-B に変換する。 */
object NalUnits {
    /** 変換後のバイト数（長さプレフィクスが4バイトなら入力と同じ）。不正なら -1。 */
    fun annexBSize(src: ByteArray, off: Int, len: Int, lengthSize: Int): Int {
        var p = off
        val end = off + len
        var total = 0
        while (p < end) {
            if (end - p < lengthSize) return -1
            val n = readLen(src, p, lengthSize)
            if (n < 0 || n > end - p - lengthSize) return -1
            total += 4 + n
            p += lengthSize + n
        }
        return total
    }

    /** dst の現在位置に書き込む。戻り値はキーフレーム(IDR/IRAP)を含むか。 */
    fun writeAnnexB(src: ByteArray, off: Int, len: Int, lengthSize: Int, hevc: Boolean, dst: ByteBuffer): Boolean {
        var p = off
        val end = off + len
        var key = false
        while (p < end) {
            val n = readLen(src, p, lengthSize)
            p += lengthSize
            dst.put(0); dst.put(0); dst.put(0); dst.put(1)
            dst.put(src, p, n)
            if (n > 0 && isKeyNal(src[p], hevc)) key = true
            p += n
        }
        return key
    }

    fun containsKeyFrame(src: ByteArray, off: Int, len: Int, lengthSize: Int, hevc: Boolean): Boolean {
        var p = off
        val end = off + len
        while (end - p >= lengthSize) {
            val n = readLen(src, p, lengthSize)
            p += lengthSize
            if (n <= 0 || n > end - p) return false
            if (isKeyNal(src[p], hevc)) return true
            p += n
        }
        return false
    }

    /** ログ用: NAL 種別の一覧 */
    fun nalTypes(src: ByteArray, off: Int, len: Int, lengthSize: Int, hevc: Boolean): List<Int> {
        val out = ArrayList<Int>()
        var p = off
        val end = off + len
        while (end - p >= lengthSize) {
            val n = readLen(src, p, lengthSize)
            p += lengthSize
            if (n <= 0 || n > end - p) break
            out += if (hevc) (src[p].toInt() shr 1) and 0x3F else src[p].toInt() and 0x1F
            p += n
        }
        return out
    }

    private fun isKeyNal(header: Byte, hevc: Boolean): Boolean =
        if (hevc) ((header.toInt() shr 1) and 0x3F) in 16..21 else (header.toInt() and 0x1F) == H264.NAL_IDR

    private fun readLen(a: ByteArray, p: Int, size: Int): Int {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (a[p + i].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }
}

/** 映像データの受け口（MediaCodec ラッパーが実装）。 */
interface VideoSink {
    fun onVideoFormat(format: VideoFormat)
    /** sample の sdat 範囲をデコーダへ。src はこの呼び出し中だけ有効。 */
    fun onVideoFrame(src: ByteArray, off: Int, len: Int, format: VideoFormat, isKeyFrame: Boolean, arrivalNanos: Long)
}

/**
 * FEED から取り出した CMSampleBuffer を処理し、SPS/PPS（フォーマット）の変化検出と
 * フレームの受け渡しを行う。UI スレッドとは無関係に USB 受信スレッド上で動く。
 */
class VideoStreamProcessor(
    private val sink: VideoSink,
    private val lowLatencySps: Boolean = true,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    var currentFormat: VideoFormat? = null
        private set
    private var waitingForKeyFrame = true

    var framesForwarded = 0L; private set
    var framesDroppedWaitingKey = 0L; private set
    var formatChanges = 0; private set
    var lastError: String? = null; private set

    fun onFormatDescription(fd: FormatDescription) {
        if (!fd.isVideo) return
        val f = try {
            VideoFormat.fromFormatDescription(fd, lowLatencySps)
        } catch (e: Exception) {
            lastError = "format: ${e.message}"
            return
        }
        if (!f.isSameStream(currentFormat)) {
            currentFormat = f
            formatChanges++
            waitingForKeyFrame = true
            sink.onVideoFormat(f)
        }
    }

    fun onSample(sample: CMSampleBuffer) {
        val arrival = nanoTime()
        sample.formatDescription?.let { onFormatDescription(it) }
        val format = currentFormat ?: run { framesDroppedWaitingKey++; return }
        if (!sample.hasSampleData || sample.sampleDataLength == 0) return
        val hevc = format.mime == VideoFormat.MIME_HEVC
        val key = NalUnits.containsKeyFrame(sample.buffer, sample.sampleDataOffset, sample.sampleDataLength, format.nalLengthSize, hevc)
        if (waitingForKeyFrame) {
            if (!key) { framesDroppedWaitingKey++; return }
            waitingForKeyFrame = false
        }
        framesForwarded++
        sink.onVideoFrame(sample.buffer, sample.sampleDataOffset, sample.sampleDataLength, format, key, arrival)
    }

    /** デコーダ再生成などで参照フレームが失われた場合に呼ぶ。 */
    fun requireKeyFrame() { waitingForKeyFrame = true }
}

package io.github.sekimotosa.c2mirror.video

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * MediaCodec（非同期モード）による H.264/HEVC デコードと Surface への描画。
 *
 * 低遅延化のための方針:
 * - 入力バッファが空いていれば USB 受信スレッドから直接 queueInputBuffer する（中間キューを経由しない）
 * - 出力はタイムスタンプ同期をせず、デコード完了次第すぐに描画する（古いフレームは SurfaceFlinger が破棄）
 * - KEY_LOW_LATENCY / KEY_PRIORITY(リアルタイム) / ベンダー固有の低遅延キーを設定
 * - SPS の max_num_reorder_frames=0 化（[H264.rewriteSpsForLowLatency]）でデコーダ内の溜め込みを防ぐ
 * - 入力待ちが溜まりすぎた場合は破棄して次のキーフレームから再開（遅延の蓄積を防ぐ）
 *
 * コーデックの生成・破棄・Surface 切り替えはすべて専用スレッドで行う。
 */
class VideoDecoder(private val callbacks: Callbacks) : VideoSink {

    interface Callbacks {
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onFirstFrameRendered(decoderName: String)
        fun onDecoderConfigured(decoderName: String, format: VideoFormat, lowLatency: Boolean)
        fun onDecoderError(message: String)
    }

    private val thread = HandlerThread("c2m-decoder", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Object()

    // 以下 lock で保護
    private var codec: MediaCodec? = null
    private var generation = 0
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<PendingFrame>()
    private var waitForKey = true
    private var format: VideoFormat? = null
    private var maxInputSize = 0

    // decoder スレッドのみ
    private var targetSurface: Surface? = null
    private var dummyReader: ImageReader? = null
    private var outputIsDummy = false
    private var decoderName = ""
    private var firstFrameRendered = false
    private var consecutiveErrors = 0
    @Volatile private var released = false

    // 統計
    @Volatile var framesQueued = 0L; private set
    @Volatile var framesRendered = 0L; private set
    @Volatile var framesDropped = 0L; private set
    @Volatile var backlogFlushes = 0; private set
    /** 入力投入からデコード出力までの時間（指数移動平均, ms） */
    @Volatile var decodeLatencyMs = 0.0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var currentDecoderName: String = ""; private set
    @Volatile var lowLatencyEnabled = false; private set

    private class PendingFrame(val data: ByteArray, val size: Int, val key: Boolean, val ptsUs: Long, val format: VideoFormat)

    // ---- VideoSink（USB 受信スレッドから呼ばれる） ----

    override fun onVideoFormat(format: VideoFormat) {
        handler.post { configure(format) }
    }

    override fun onVideoFrame(src: ByteArray, off: Int, len: Int, format: VideoFormat, isKeyFrame: Boolean, arrivalNanos: Long) {
        val hevc = format.mime == VideoFormat.MIME_HEVC
        val size = NalUnits.annexBSize(src, off, len, format.nalLengthSize)
        if (size < 0) { framesDropped++; lastError = "malformed NAL length"; return }
        val ptsUs = arrivalNanos / 1000
        synchronized(lock) {
            if (released) return
            if (waitForKey) {
                if (!isKeyFrame) { framesDropped++; return }
            }
            val c = codec
            if (c != null && this.format === format && pending.isEmpty() && freeInputs.isNotEmpty()) {
                val idx = freeInputs.removeFirst()
                if (queueDirect(c, idx, src, off, len, size, format.nalLengthSize, hevc, isKeyFrame, ptsUs)) {
                    waitForKey = false
                }
                return
            }
            // 入力バッファが空いていない: Annex-B に変換して保留
            val buf = ByteArray(size)
            NalUnits.writeAnnexB(src, off, len, format.nalLengthSize, hevc, ByteBuffer.wrap(buf))
            if (pending.size >= MAX_PENDING) {
                // デコーダが追いつかない: 古いフレームを捨ててキーフレームから再開（遅延を蓄積させない）
                framesDropped += pending.size
                pending.clear()
                backlogFlushes++
                waitForKey = true
                if (!isKeyFrame) { framesDropped++; return }
            }
            pending.addLast(PendingFrame(buf, size, isKeyFrame, ptsUs, format))
            waitForKey = false
        }
    }

    private fun queueDirect(c: MediaCodec, idx: Int, src: ByteArray, off: Int, len: Int, size: Int, lengthSize: Int,
                            hevc: Boolean, key: Boolean, ptsUs: Long): Boolean {
        return try {
            val ib = c.getInputBuffer(idx) ?: return false
            ib.clear()
            if (ib.capacity() < size) {
                framesDropped++
                lastError = "input buffer too small (${ib.capacity()} < $size)"
                maxInputSize = maxOf(maxInputSize, size * 2)
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                false
            } else {
                NalUnits.writeAnnexB(src, off, len, lengthSize, hevc, ib)
                c.queueInputBuffer(idx, 0, size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                framesQueued++
                true
            }
        } catch (e: Exception) {
            lastError = "queueInputBuffer: ${e.message}"
            false
        }
    }

    private fun queuePending(c: MediaCodec, idx: Int, f: PendingFrame): Boolean = try {
        val ib = c.getInputBuffer(idx)
        if (ib == null || ib.capacity() < f.size) {
            framesDropped++
            maxInputSize = maxOf(maxInputSize, f.size * 2)
            c.queueInputBuffer(idx, 0, 0, f.ptsUs, 0)
            false
        } else {
            ib.clear(); ib.put(f.data, 0, f.size)
            c.queueInputBuffer(idx, 0, f.size, f.ptsUs, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            framesQueued++
            true
        }
    } catch (e: Exception) {
        lastError = "queueInputBuffer: ${e.message}"; false
    }

    // ---- Surface（UI スレッドから） ----

    fun setSurface(surface: Surface?, waitMs: Long = 0) {
        val done = java.util.concurrent.CountDownLatch(1)
        val posted = handler.post {
            try { switchSurface(surface) } finally { done.countDown() }
        }
        if (!posted) return
        if (waitMs > 0) done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun switchSurface(surface: Surface?) {
        run {
            targetSurface = surface?.takeIf { it.isValid }
            val c = synchronized(lock) { codec } ?: return@run
            val out = targetSurface ?: dummySurface(format)
            if (out == null) return@run
            try {
                c.setOutputSurface(out)
                outputIsDummy = targetSurface == null
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface failed, recreating codec", e)
                format?.let { configure(it, force = true) }
            }
        }
    }

    private fun dummySurface(f: VideoFormat?): Surface? {
        dummyReader?.let { return it.surface }
        val w = f?.width?.takeIf { it > 0 } ?: 1280
        val h = f?.height?.takeIf { it > 0 } ?: 720
        val fmt = if (Build.VERSION.SDK_INT >= 29) ImageFormat.PRIVATE else ImageFormat.YUV_420_888
        return try {
            ImageReader.newInstance(w, h, fmt, 2).also { r ->
                r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                dummyReader = r
            }.surface
        } catch (e: Exception) {
            Log.w(TAG, "dummy surface failed", e); null
        }
    }

    // ---- コーデック生成（decoder スレッド） ----

    private fun configure(f: VideoFormat, force: Boolean = false) {
        if (released) return
        if (!force && f.isSameStream(format) && synchronized(lock) { codec } != null) return
        // フォーマット通知直後に届いた（新フォーマットの）キーフレームは保持したまま作り直す
        releaseCodec(keepPendingFor = f)
        val surface = targetSurface ?: dummySurface(f)
        if (surface == null) { fail("no output surface"); return }
        outputIsDummy = targetSurface == null

        val candidates = findDecoders(f.mime, f.width, f.height)
        var lastErr: Exception? = null
        for (info in candidates) {
            for (useExtras in listOf(true, false)) {
                val c = try { MediaCodec.createByCodecName(info.name) } catch (e: Exception) { lastErr = e; break }
                try {
                    val (mf, lowLatency) = buildFormat(f, info, useExtras)
                    val gen: Int
                    synchronized(lock) {
                        generation++
                        gen = generation
                        freeInputs.clear()
                    }
                    c.setCallback(Callback(gen), handler)
                    c.configure(mf, surface, null, 0)
                    c.start()
                    synchronized(lock) {
                        codec = c
                        format = f
                    }
                    decoderName = info.name
                    currentDecoderName = info.name
                    lowLatencyEnabled = lowLatency
                    firstFrameRendered = false
                    callbacks.onDecoderConfigured(info.name, f, lowLatency)
                    if (f.width > 0 && f.height > 0) callbacks.onVideoSizeChanged(f.width, f.height)
                    return
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "configure ${info.name} (extras=$useExtras) failed", e)
                    runCatching { c.release() }
                }
            }
        }
        fail("decoder configure failed for ${f.mime}: ${lastErr?.message ?: "no decoder found"}")
    }

    private fun buildFormat(f: VideoFormat, info: MediaCodecInfo, extras: Boolean): Pair<MediaFormat, Boolean> {
        val w = if (f.width > 0) f.width else 1920
        val h = if (f.height > 0) f.height else 1080
        val mf = MediaFormat.createVideoFormat(f.mime, w, h)
        f.csd.forEachIndexed { i, b -> mf.setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
        maxInputSize = maxOf(maxInputSize, w * h * 3 / 2, 1 shl 20)
        mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        var lowLatency = false
        if (extras) {
            if (Build.VERSION.SDK_INT >= 30) {
                val caps = runCatching { info.getCapabilitiesForType(f.mime) }.getOrNull()
                if (caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true) {
                    mf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    lowLatency = true
                }
            }
            mf.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = リアルタイム
            mf.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            val name = info.name.lowercase()
            // ベンダー拡張（未対応のキーは無視される）
            when {
                name.contains("qcom") || name.contains("qti") -> {
                    mf.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                    mf.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                    lowLatency = true
                }
                name.contains("exynos") -> {
                    mf.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1); lowLatency = true
                }
                name.contains("amlogic") -> {
                    mf.setInteger("vendor.low-latency.enable", 1); lowLatency = true
                }
                name.contains("mtk") || name.contains("mediatek") -> {
                    mf.setInteger("vendor.mtk.vdec.lowlatency.enable", 1)
                }
            }
        }
        return mf to lowLatency
    }

    private fun findDecoders(mime: String, w: Int, h: Int): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        fun isHw(i: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated
            else !i.name.startsWith("OMX.google.") && !i.name.startsWith("c2.android.")
        fun supportsSize(i: MediaCodecInfo) = runCatching {
            val vc = i.getCapabilitiesForType(mime).videoCapabilities
            vc == null || w <= 0 || h <= 0 || vc.isSizeSupported(w, h) || vc.isSizeSupported(h, w)
        }.getOrDefault(true)
        fun lowLat(i: MediaCodecInfo) = Build.VERSION.SDK_INT >= 30 && runCatching {
            i.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
        return all.sortedWith(compareByDescending<MediaCodecInfo> { supportsSize(it) }
            .thenByDescending { isHw(it) }
            .thenByDescending { lowLat(it) })
    }

    private inner class Callback(private val gen: Int) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (gen != generation || codec !== c) return
                if (pending.isNotEmpty()) {
                    queuePending(c, index, pending.removeFirst()) // 失敗時も index は消費済み
                    return
                }
                freeInputs.addLast(index)
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (gen != generation) return
            try {
                val render = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                // タイムスタンプ同期をせず即時描画（最小遅延）
                c.releaseOutputBuffer(index, render)
                consecutiveErrors = 0
                if (render) {
                    framesRendered++
                    val lat = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000.0
                    if (lat in 0.0..5000.0) decodeLatencyMs = if (decodeLatencyMs == 0.0) lat else decodeLatencyMs * 0.9 + lat * 0.1
                    if (!firstFrameRendered && !outputIsDummy) {
                        firstFrameRendered = true
                        callbacks.onFirstFrameRendered(decoderName)
                    }
                }
            } catch (e: Exception) {
                lastError = "releaseOutputBuffer: ${e.message}"
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (gen != generation) return
            lastError = "codec error: ${e.diagnosticInfo}"
            Log.w(TAG, "codec error", e)
            consecutiveErrors++
            val f = format
            if (consecutiveErrors <= 3 && f != null) {
                handler.post { configure(f, force = true) }
            } else {
                fail("decoder error: ${e.diagnosticInfo}")
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, mf: MediaFormat) {
            if (gen != generation) return
            var w = mf.getIntegerOr(MediaFormat.KEY_WIDTH, 0)
            var h = mf.getIntegerOr(MediaFormat.KEY_HEIGHT, 0)
            if (mf.containsKey("crop-right") && mf.containsKey("crop-left")) {
                w = mf.getInteger("crop-right") - mf.getInteger("crop-left") + 1
            }
            if (mf.containsKey("crop-bottom") && mf.containsKey("crop-top")) {
                h = mf.getInteger("crop-bottom") - mf.getInteger("crop-top") + 1
            }
            if (w > 0 && h > 0) callbacks.onVideoSizeChanged(w, h)
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def

    private fun fail(msg: String) {
        lastError = msg
        callbacks.onDecoderError(msg)
    }

    private fun releaseCodec(keepPendingFor: VideoFormat? = null) {
        val c = synchronized(lock) {
            generation++
            freeInputs.clear()
            if (keepPendingFor == null) {
                pending.clear()
            } else {
                pending.removeAll { it.format !== keepPendingFor }
                while (pending.isNotEmpty() && !pending.first().key) { pending.removeFirst(); framesDropped++ }
            }
            waitForKey = pending.isEmpty()
            codec.also { codec = null }
        }
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    /** USB セッション終了時など。スレッドも停止する。 */
    fun release() {
        released = true
        handler.post {
            releaseCodec()
            dummyReader?.close(); dummyReader = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "C2M.Decoder"
        /** 入力待ちの上限（約 0.5 秒分）。超えたら破棄してキーフレームから再開する。 */
        const val MAX_PENDING = 30
    }
}

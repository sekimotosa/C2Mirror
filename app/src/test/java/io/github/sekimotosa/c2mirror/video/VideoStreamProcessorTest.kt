package io.github.sekimotosa.c2mirror.video

import io.github.sekimotosa.c2mirror.Fixtures
import io.github.sekimotosa.c2mirror.protocol.CMSampleBuffer
import io.github.sekimotosa.c2mirror.protocol.Dicts
import io.github.sekimotosa.c2mirror.protocol.FormatDescription
import io.github.sekimotosa.c2mirror.protocol.Magic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamProcessorTest {
    private val formats = ArrayList<VideoFormat>()
    private val frames = ArrayList<Pair<Int, Boolean>>()
    private val sink = object : VideoSink {
        override fun onVideoFormat(format: VideoFormat) { formats += format }
        override fun onVideoFrame(src: ByteArray, off: Int, len: Int, format: VideoFormat, isKeyFrame: Boolean, arrivalNanos: Long) {
            frames += len to isKeyFrame
        }
    }
    private val proc = VideoStreamProcessor(sink)

    private fun sample(name: String, payloadOffset: Int = 16): CMSampleBuffer {
        val b = Fixtures.body(name)
        return CMSampleBuffer.parse(b, payloadOffset, b.size, Magic.MEDIA_VIDEO)
    }

    private fun cvrpFormat(): FormatDescription {
        val b = Fixtures.body("cvrp-request")
        return Dicts.parseStringDict(b, 32, b.size)["FormatDescription"] as FormatDescription
    }

    @Test fun formatFromCvrpThenFrames() {
        proc.onFormatDescription(cvrpFormat())
        assertEquals(1, formats.size)
        val f = formats[0]
        assertEquals(VideoFormat.MIME_AVC, f.mime)
        assertEquals(1126, f.width); assertEquals(2436, f.height)
        assertEquals(2, f.csd.size)
        assertEquals(7, f.csd[0][4].toInt() and 0x1F)
        assertEquals(8, f.csd[1][4].toInt() and 0x1F)

        // P フレームはキーフレーム前なので破棄
        proc.onSample(sample("asyn-feed-nofdsc"))
        assertTrue(frames.isEmpty())
        assertEquals(1L, proc.framesDroppedWaitingKey)

        // IDR（同じ fdsc を含む）→ 再設定せずに転送
        proc.onSample(sample("asyn-feed"))
        assertEquals(1, formats.size)
        assertEquals(90750 to true, frames.single())

        proc.onSample(sample("asyn-feed-nofdsc"))
        assertEquals(2, frames.size)
        assertEquals(false, frames[1].second)
    }

    @Test fun framesBeforeAnyFormatAreDropped() {
        proc.onSample(sample("asyn-feed-nofdsc"))
        assertTrue(frames.isEmpty())
        assertTrue(formats.isEmpty())
    }

    @Test fun sampleWithoutDataIsIgnored() {
        proc.onFormatDescription(cvrpFormat())
        proc.onSample(sample("asyn-feed-ttas-only"))
        assertTrue(frames.isEmpty())
    }
}

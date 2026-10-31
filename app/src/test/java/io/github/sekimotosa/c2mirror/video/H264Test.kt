package io.github.sekimotosa.c2mirror.video

import io.github.sekimotosa.c2mirror.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class H264Test {
    /** 参考実装キャプチャ（iPhone の CVRP / FEED 内 avcC）の SPS */
    private val iphoneSps = Fixtures.hex("27640033ac5680470133e69e6e04040404")

    @Test fun parseIphoneSps() {
        val sps = H264.parseSps(iphoneSps)
        assertEquals(100, sps.profileIdc) // High
        assertEquals(51, sps.levelIdc)
        assertEquals(1126, sps.width)
        assertEquals(2436, sps.height)
    }

    @Test fun lowLatencyRewriteSetsReorderZeroAndKeepsDimensions() {
        val original = H264.parseSps(iphoneSps)
        val rewritten = H264.rewriteSpsForLowLatency(iphoneSps)
        if (rewritten == null) {
            // 既に reorder=0 なら書き換え不要
            assertEquals(0, original.maxNumReorderFrames)
            return
        }
        val s = H264.parseSps(rewritten)
        assertTrue(s.bitstreamRestriction)
        assertEquals(0, s.maxNumReorderFrames)
        assertEquals(maxOf(1, original.maxNumRefFrames), s.maxDecFrameBuffering)
        assertEquals(original.width, s.width)
        assertEquals(original.height, s.height)
        assertEquals(original.profileIdc, s.profileIdc)
        assertEquals(original.maxNumRefFrames, s.maxNumRefFrames)
        // 二重適用しても変化しない
        assertNull(H264.rewriteSpsForLowLatency(rewritten))
    }

    @Test fun rewriteSpsWithoutVui() {
        // Baseline 640x480, VUI なしの SPS を組み立てる
        val w = BitWriter()
        w.bits(66, 8); w.bits(0xC0, 8); w.bits(30, 8)
        w.ue(0); w.ue(0); w.ue(2); w.ue(1); w.flag(false)
        w.ue(39); w.ue(29); w.flag(true); w.flag(true); w.flag(false)
        w.flag(false) // vui_parameters_present_flag
        w.trailing()
        val sps = byteArrayOf(0x67) + Rbsp.escape(w.toByteArray())
        val orig = H264.parseSps(sps)
        assertEquals(640, orig.width); assertEquals(480, orig.height)
        assertTrue(!orig.vuiPresent)
        val s = H264.parseSps(H264.rewriteSpsForLowLatency(sps)!!)
        assertTrue(s.vuiPresent && s.bitstreamRestriction)
        assertEquals(0, s.maxNumReorderFrames)
        assertEquals(1, s.maxDecFrameBuffering)
        assertEquals(640, s.width); assertEquals(480, s.height)
    }

    @Test fun rbspEscapeRoundTrip() {
        val rnd = Random(1)
        repeat(200) {
            val data = ByteArray(rnd.nextInt(1, 64)) { if (rnd.nextInt(3) == 0) 0 else rnd.nextInt(0, 5).toByte() }
            val esc = Rbsp.escape(data)
            // エスケープ後に 00 00 0x(x<=3) が残っていないこと
            for (i in 0 until esc.size - 2) {
                if (esc[i].toInt() == 0 && esc[i + 1].toInt() == 0) assertTrue(esc[i + 2].toInt() and 0xFF == 3 || esc[i + 2].toInt() and 0xFF > 3)
            }
            assertArrayEquals(data, Rbsp.unescape(esc))
        }
    }

    @Test fun expGolombRoundTrip() {
        val w = BitWriter()
        val values = listOf(0L, 1, 2, 3, 7, 8, 255, 256, 65535, 1_000_000)
        values.forEach { w.ue(it) }
        w.trailing()
        val r = BitReader(w.toByteArray())
        values.forEach { assertEquals(it, r.ue()) }
    }

    @Test fun annexBConversionOfIphoneIdr() {
        val b = Fixtures.body("asyn-feed")
        val sb = io.github.sekimotosa.c2mirror.protocol.CMSampleBuffer.parse(b, 16, b.size, io.github.sekimotosa.c2mirror.protocol.Magic.MEDIA_VIDEO)
        val size = NalUnits.annexBSize(b, sb.sampleDataOffset, sb.sampleDataLength, 4)
        assertEquals(sb.sampleDataLength, size)
        val out = ByteBuffer.allocate(size)
        val key = NalUnits.writeAnnexB(b, sb.sampleDataOffset, sb.sampleDataLength, 4, false, out)
        assertTrue(key)
        val a = out.array()
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), a.copyOfRange(0, 4))
        assertEquals(6, a[4].toInt() and 0x1F) // SEI
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), a.copyOfRange(4 + 30, 4 + 30 + 4))
        assertEquals(5, a[4 + 30 + 4].toInt() and 0x1F) // IDR
    }

    @Test fun malformedNalLengthDetected() {
        val d = byteArrayOf(0, 0, 0, 10, 1, 2, 3)
        assertEquals(-1, NalUnits.annexBSize(d, 0, d.size, 4))
    }

    @Test fun avcCParse() {
        val avcc = Fixtures.hex("01640033ffe1001127640033ac5680470133e69e6e0404040401000428ee3cb0fdf8f800")
        val c = AvcDecoderConfig.parse(avcc)
        assertEquals(4, c.nalLengthSize)
        assertArrayEquals(iphoneSps, c.sps.single())
        assertArrayEquals(Fixtures.hex("28ee3cb0"), c.pps.single())
    }
}

class H264IphoneSpsTest {
    /** 実機キャプチャの SPS は VUI はあるが bitstream_restriction が無い → デコーダが並べ替え待ちをする可能性があるため書き換え対象 */
    @Test fun iphoneSpsNeedsRewrite() {
        val sps = Fixtures.hex("27640033ac5680470133e69e6e04040404")
        val info = H264.parseSps(sps)
        assertTrue(info.vuiPresent)
        assertTrue(!info.bitstreamRestriction)
        assertEquals(1, info.maxNumRefFrames)
        assertArrayEquals(Fixtures.hex("27640033ac5680470133e69e6e0404040da08846a0"), H264.rewriteSpsForLowLatency(sps))
    }
}

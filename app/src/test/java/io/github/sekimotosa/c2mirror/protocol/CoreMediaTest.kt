package io.github.sekimotosa.c2mirror.protocol

import io.github.sekimotosa.c2mirror.Fixtures
import io.github.sekimotosa.c2mirror.Fixtures.toHex
import io.github.sekimotosa.c2mirror.video.AvcDecoderConfig
import io.github.sekimotosa.c2mirror.video.NalUnits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 参考実装 coremedia パッケージのテスト相当 */
class CoreMediaTest {

    @Test fun indexDict() {
        val d = Fixtures.load("coremedia/intdict.bin")
        val dict = Dicts.parseIndexDict(d, 0, d.size)
        assertEquals(2, dict.entries.size)
        assertEquals(49, dict.entries[0].first)
        val nested = dict.entries[0].second as IndexKeyDict
        assertEquals(105, nested.entries[0].first)
        assertEquals(36, (nested.entries[0].second as ByteArray).size)
        assertEquals(52, dict.entries[1].first)
        assertEquals("H.264", dict.entries[1].second)
    }

    @Test fun stringDict() {
        val d = Fixtures.load("coremedia/dict.bin")
        val dict = Dicts.parseStringDict(d, 0, d.size)
        assertEquals(listOf("Valeria", "HEVCDecoderSupports444", "DisplaySize"), dict.entries.map { it.first })
        val size = dict["DisplaySize"] as StringKeyDict
        assertEquals(1920.0, (size["Width"] as NSNumber).doubleValue, 0.0)
        assertEquals(1200.0, (size["Height"] as NSNumber).doubleValue, 0.0)
        // ラウンドトリップ
        assertArrayEquals(d, Dicts.serialize(dict))
    }

    @Test fun complexDictContainsFormatDescription() {
        val d = Fixtures.load("coremedia/complex_dict.bin")
        val dict = Dicts.parseStringDict(d, 0, d.size)
        assertEquals(3, dict.entries.size)
        assertTrue(dict.entries[2].second is FormatDescription)
    }

    @Test fun videoFormatDescription() {
        val d = Fixtures.load("coremedia/formatdescriptor.bin")
        val fd = FormatDescription.parse(d, 0, d.size)
        assertEquals(Magic.MEDIA_VIDEO, fd.mediaType)
        val avcc = AvcDecoderConfig.parse(fd.decoderConfigurationRecord()!!)
        // 参考実装は名前が逆（PPS/SPS）だが、avcC の最初は SPS(NAL type 7)、次が PPS(type 8)
        assertEquals("27640033ac5680470133e69e6e04040404", avcc.sps[0].toHex())
        assertEquals("28ee3cb0", avcc.pps[0].toHex())
        assertEquals(4, avcc.nalLengthSize)
    }

    @Test fun audioFormatDescription() {
        val d = Fixtures.load("coremedia/formatdescriptor-audio.bin")
        val fd = FormatDescription.parse(d, 0, d.size)
        assertEquals(Magic.MEDIA_SOUND, fd.mediaType)
        assertEquals(48000.0, fd.asbd!!.sampleRate, 0.0)
    }

    @Test fun feedSampleBuffer() {
        val b = Fixtures.body("asyn-feed")
        val p = PacketView(b, 0, b.size)
        assertEquals(Magic.FEED, p.subtype)
        assertEquals(0x7ffb5cc32f60L, p.clockRef)
        val sb = CMSampleBuffer.parse(b, p.asynPayload, p.end, Magic.MEDIA_VIDEO)
        assertTrue(sb.hasFormatDescription)
        assertEquals(1126, sb.formatDescription!!.width)
        assertEquals(CMTime.FLAGS_HAS_BEEN_ROUNDED, sb.outputPresentationTimestamp!!.flags)
        assertEquals(0x176a7L, sb.outputPresentationTimestamp!!.seconds())
        assertEquals(1, sb.sampleTimingInfo.size)
        assertEquals(0x176a7L, sb.sampleTimingInfo[0].presentationTimeStamp.seconds())
        assertEquals(90750, sb.sampleDataLength)
        assertEquals(1, sb.numSamples)
        assertArrayEquals(intArrayOf(90750), sb.sampleSizes)
        assertEquals(4, sb.attachments()!!.entries.size)
        assertEquals(1, sb.sary()!!.entries.size)
        assertTrue(sb.unknownAtoms.isEmpty())
        // NAL: SEI(30 bytes) + IDR(90712 bytes)
        assertEquals(listOf(6, 5), NalUnits.nalTypes(b, sb.sampleDataOffset, sb.sampleDataLength, 4, false))
        assertTrue(NalUnits.containsKeyFrame(b, sb.sampleDataOffset, sb.sampleDataLength, 4, false))
    }

    @Test fun feedWithoutFormatDescription() {
        val b = Fixtures.body("asyn-feed-nofdsc")
        val sb = CMSampleBuffer.parse(b, 16, b.size, Magic.MEDIA_VIDEO)
        assertFalse(sb.hasFormatDescription)
        assertEquals(0x44b82fa09L, sb.outputPresentationTimestamp!!.seconds())
        assertTrue(sb.hasSampleData)
        assertFalse(NalUnits.containsKeyFrame(b, sb.sampleDataOffset, sb.sampleDataLength, 4, false))
    }

    @Test fun feedWithoutSampleData() {
        val b = Fixtures.body("asyn-feed-ttas-only")
        val sb = CMSampleBuffer.parse(b, 16, b.size, Magic.MEDIA_VIDEO)
        assertFalse(sb.hasFormatDescription)
        assertFalse(sb.hasSampleData)
    }

    @Test fun unknownAtomIsSkippedNotFatal() {
        val wire = Fixtures.load("asyn-feed-ttas-only")
        wire[32] = 0x6c; wire[33] = 0x6c; wire[34] = 0x75; wire[35] = 0x75 // 参考実装 TestUnknownMagic と同じ改変
        val sb = CMSampleBuffer.parse(wire, 20, wire.size, Magic.MEDIA_VIDEO)
        assertEquals(1, sb.unknownAtoms.size)
        assertEquals("uull", fourCCToString(sb.unknownAtoms[0])) // ワイヤ上のバイト列は "lluu"（参考実装のエラー表記）
    }

    @Test(expected = ProtocolException::class)
    fun truncatedSampleBufferFails() {
        val b = Fixtures.body("asyn-feed")
        CMSampleBuffer.parse(b, 16, b.size - 1000, Magic.MEDIA_VIDEO)
    }

    @Test fun eatSampleBuffer() {
        val b = Fixtures.body("asyn-eat")
        val p = PacketView(b, 0, b.size)
        assertEquals(Magic.EAT, p.subtype)
        assertEquals(0x133959728L, p.clockRef)
        val sb = CMSampleBuffer.parse(b, p.asynPayload, p.end, Magic.MEDIA_SOUND)
        assertTrue(sb.hasSampleData)
        assertEquals(Magic.MEDIA_SOUND, sb.formatDescription?.mediaType)
    }

    @Test fun nsNumberRoundTrip() {
        for (n in listOf(NSNumber.ofInt32(7), NSNumber.ofInt64(1L shl 40), NSNumber.ofFloat64(0.04))) {
            val b = n.toBytes()
            assertEquals(n, NSNumber.parse(b, 0, b.size))
        }
    }

    @Test fun skewCalculation() {
        val s0 = CMTime(0, 1_000_000_000, 0, 0)
        val s1 = CMTime(1_000_000_000, 1_000_000_000, 0, 0)
        val d0 = CMTime(0, 48000, 0, 0)
        val d1 = CMTime(48000, 48000, 0, 0)
        assertEquals(48000.0, CMClock.calculateSkew(s0, s1, d0, d1), 0.001)
        assertTrue(CMClock.calculateSkew(s0, s1, d0, d0).isNaN())
    }
}

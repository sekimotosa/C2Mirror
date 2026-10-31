package io.github.sekimotosa.c2mirror.protocol

import io.github.sekimotosa.c2mirror.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 参考実装 packet/sync_xxx_test.go 相当 */
class SyncPacketsTest {
    private fun view(name: String) = Fixtures.body(name).let { PacketView(it, 0, it.size) }

    @Test fun cwpa() {
        val p = view("cwpa-request1")
        assertEquals(Magic.SYNC, p.type)
        assertEquals(Magic.CWPA, p.subtype)
        assertEquals(EMPTY_CF_TYPE, p.clockRef)
        assertEquals(0x113573de0L, p.correlationId)
        assertEquals(0x1135a74e0L, p.buf.u64le(p.syncPayload))
        assertArrayEquals(Fixtures.load("cwpa-reply1"), Packets.clockRefReply(p.correlationId, 0x00007FA66CE20CB0L))
    }

    @Test fun cvrp() {
        val p = view("cvrp-request")
        assertEquals(Magic.CVRP, p.subtype)
        assertEquals(EMPTY_CF_TYPE, p.clockRef)
        assertEquals(0x1135659d0L, p.correlationId)
        assertEquals(0x113538da0L, p.buf.u64le(p.syncPayload))
        val dict = Dicts.parseStringDict(p.buf, p.syncPayload + 8, p.end)
        assertEquals(3, dict.entries.size)
        val hw = dict["PreparedQueueHighWaterLevel"] as StringKeyDict
        assertEquals(5L, (hw["value"] as NSNumber).longValue)
        assertEquals(30L, (hw["timescale"] as NSNumber).longValue)
        val fd = dict["FormatDescription"] as FormatDescription
        assertTrue(fd.isVideo)
        assertEquals(1126, fd.width)
        assertEquals(2436, fd.height)
        assertEquals(Magic.CODEC_AVC1, fd.codec)
        assertNotNull(fd.decoderConfigurationRecord())
        assertArrayEquals(Fixtures.load("cvrp-reply"), Packets.clockRefReply(p.correlationId, 0x00007FA66CD10250L))
    }

    @Test fun clok() {
        val p = view("clok-request")
        assertEquals(Magic.CLOK, p.subtype)
        assertEquals(0x7fa66cd10250L, p.clockRef)
        assertEquals(0x113584970L, p.correlationId)
        assertArrayEquals(Fixtures.load("clok-reply"), Packets.clockRefReply(p.correlationId, 0x00007FA67CC17980L))
    }

    @Test fun time() {
        val p = view("time-request1")
        assertEquals(Magic.TIME, p.subtype)
        assertEquals(0x7fa67cc17980L, p.clockRef)
        assertEquals(0x113223d50L, p.correlationId)
        val t = CMTime(0x0000BA62C442E1E1L, 0x3B9ACA00, CMTime.FLAGS_HAS_BEEN_ROUNDED, 0)
        assertArrayEquals(Fixtures.load("time-reply1"), Packets.timeReply(p.correlationId, t))
    }

    @Test fun afmt() {
        val p = view("afmt-request")
        assertEquals(Magic.AFMT, p.subtype)
        assertEquals(0x7fa66ce20cb0L, p.clockRef)
        assertEquals(0x113229d80L, p.correlationId)
        val asbd = AudioStreamBasicDescription.parse(p.buf, p.syncPayload, p.end)
        assertEquals(AudioStreamBasicDescription.DEFAULT.copy(formatFlags = 0x4C), asbd)
        assertArrayEquals(Fixtures.load("afmt-reply"), Packets.afmtReply(p.correlationId))
    }

    @Test fun og() {
        val p = view("og-request")
        assertEquals(Magic.OG, p.subtype)
        assertEquals(0x102d32f30L, p.correlationId)
        assertArrayEquals(Fixtures.load("og-reply"), Packets.emptyReply(p.correlationId))
    }

    @Test fun skew() {
        val p = view("skew-request")
        assertEquals(Magic.SKEW, p.subtype)
        assertEquals(0x102fdb960L, p.correlationId)
        assertArrayEquals(Fixtures.load("skew-reply"), Packets.skewReply(p.correlationId, 48000.0))
    }

    @Test fun stop() {
        val p = view("stop-request")
        assertEquals(Magic.STOP, p.subtype)
        assertEquals(0x102fd4910L, p.correlationId)
        assertArrayEquals(Fixtures.load("stop-reply"), Packets.emptyReply(p.correlationId))
    }

    @Test(expected = ProtocolException::class)
    fun cwpaWithNonEmptyClockRefIsRejected() {
        val b = Fixtures.body("cwpa-request1")
        b[5] = 0xFF.toByte()
        Packets.requireEmptyClockRef(PacketView(b, 0, b.size), "CWPA")
    }

    @Test fun asynInfoPackets() {
        val sprp = view("asyn-sprp")
        assertEquals(Magic.SPRP, sprp.subtype)
        assertEquals(0x11123bc18L, sprp.clockRef)
        val (k, v) = Dicts.parseKeyValue(sprp.buf, sprp.asynPayload, sprp.end)
        assertEquals("ObeyEmptyMediaMarkers", k)
        assertEquals(true, v)

        val srat = view("asyn-srat")
        assertEquals(Magic.SRAT, srat.subtype)
        val t = CMTime.parse(srat.buf, srat.asynPayload + 8, srat.end)
        assertEquals(1_000_000_000, t.scale)
        assertEquals(1570648854000190667L, t.value)

        assertEquals(Magic.TBAS, view("asyn-tbas").subtype)
        assertEquals(Magic.TJMP, view("asyn-tjmp").subtype)
        val rels = view("asyn-rels")
        assertEquals(Magic.RELS, rels.subtype)
        assertEquals(0x7fba35608a00L, rels.clockRef)
    }
}

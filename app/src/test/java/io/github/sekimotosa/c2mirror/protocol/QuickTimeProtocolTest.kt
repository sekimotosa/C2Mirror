package io.github.sekimotosa.c2mirror.protocol

import io.github.sekimotosa.c2mirror.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** セッション確立手順（technical_documentation.md 2.1）に沿った送受信の検証 */
class QuickTimeProtocolTest {
    private val written = CopyOnWriteArrayList<ByteArray>()
    private val events = CopyOnWriteArrayList<ProtocolEvent>()
    private val videos = ArrayList<CMSampleBuffer>()
    private var fakeNow = 0L
    private val proto = QuickTimeProtocol(
        { written += it },
        object : ProtocolListener {
            override fun onVideoSample(sample: CMSampleBuffer) { videos += sample }
            override fun onEvent(event: ProtocolEvent) { events += event }
        },
        { id -> CMClock(id) { fakeNow } },
    )

    private fun feed(name: String) { val b = Fixtures.body(name); proto.handlePacket(b, 0, b.size) }
    private fun subtypeOf(b: ByteArray) = b.i32le(16)

    @Test fun ping() {
        val b = Packets.ping().copyOfRange(4, 16)
        proto.handlePacket(b, 0, b.size)
        assertArrayEquals(Packets.ping(), written.single())
        assertTrue(proto.pingReceived)
    }

    @Test fun fullHandshakeAndFeed() {
        feed("cwpa-request1")
        val dev = 0x1135a74e0L
        assertEquals(4, written.size)
        assertArrayEquals(Packets.hpd1(), written[0])
        assertArrayEquals(Packets.hpd1(), written[1])
        assertArrayEquals(Packets.clockRefReply(0x113573de0L, dev + 1000), written[2])
        assertArrayEquals(Packets.hpa1(dev), written[3])
        written.clear()

        feed("afmt-request")
        assertArrayEquals(Fixtures.load("afmt-reply"), written.single())
        written.clear()

        feed("cvrp-request")
        val vdev = 0x113538da0L
        assertEquals(2, written.size)
        assertArrayEquals(Packets.need(vdev), written[0])
        assertArrayEquals(Packets.clockRefReply(0x1135659d0L, vdev + 0x1000AF), written[1])
        val neg = events.filterIsInstance<ProtocolEvent.VideoClockNegotiated>().single()
        assertNotNull(neg.format)
        assertEquals(1126, neg.format!!.width)
        written.clear()

        feed("clok-request")
        assertArrayEquals(Packets.clockRefReply(0x113584970L, 0x7fa66cd10250L + 0x10000), written.single())
        written.clear()

        fakeNow = 0x0000BA62C442E1E1L
        feed("time-request1")
        val t = CMTime(0x0000BA62C442E1E1L, 1_000_000_000, CMTime.FLAGS_HAS_BEEN_ROUNDED, 0)
        assertArrayEquals(Fixtures.load("time-reply1"), Packets.timeReply(0x113223d50L, t))
        assertArrayEquals(Fixtures.load("time-reply1"), written.single())
        written.clear()

        // FEED: 先に NEED を返し、サンプルを上位に渡す
        feed("asyn-feed")
        assertArrayEquals(Packets.need(vdev), written.single())
        assertEquals(1, videos.size)
        assertEquals(90750, videos[0].sampleDataLength)
        assertEquals(1L, proto.videoSamples)

        feed("asyn-sprp"); feed("asyn-srat"); feed("asyn-tbas"); feed("asyn-tjmp")
        assertEquals(0L, proto.unknownPackets)
    }

    @Test fun skewWithoutAudioFallsBackToSampleRate() {
        feed("skew-request")
        assertArrayEquals(Fixtures.load("skew-reply"), written.single())
    }

    @Test fun stopIsAcknowledged() {
        feed("stop-request")
        assertArrayEquals(Fixtures.load("stop-reply"), written.single())
        assertTrue(events.any { it is ProtocolEvent.StopRequested })
    }

    @Test fun unknownSyncGetsEmptyReply() {
        val b = Fixtures.body("og-request")
        b.putI32le(12, fourCC("zzzz"))
        proto.handlePacket(b, 0, b.size)
        assertArrayEquals(Packets.emptyReply(0x102d32f30L), written.single())
        assertEquals(1L, proto.unknownPackets)
    }

    @Test fun corruptFeedStillSendsNeed() {
        feed("cvrp-request")
        written.clear()
        val b = Fixtures.body("asyn-feed").copyOf(5000) // 途中で切れた FEED
        proto.handlePacket(b, 0, b.size)
        assertEquals(1, written.size)
        assertEquals(Magic.NEED, subtypeOf(written[0]))
        assertEquals(1L, proto.feedParseErrors)
        assertTrue(videos.isEmpty())
    }

    @Test fun closeSessionWaitsForRels() {
        feed("cwpa-request1")
        written.clear()
        val t = Thread {
            Thread.sleep(50)
            feed("asyn-rels"); feed("asyn-rels")
        }
        t.start()
        assertTrue(proto.closeSession(2000))
        t.join()
        assertArrayEquals(Packets.hpa0(0x1135a74e0L), written[0])
        assertArrayEquals(Packets.hpd0(), written[1])
        assertArrayEquals(Packets.hpd0(), written[2])
    }

    @Test fun closeSessionTimesOut() {
        assertFalse(proto.closeSession(50))
        assertEquals(2, written.size)
    }
}

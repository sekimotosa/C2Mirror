package io.github.sekimotosa.c2mirror.protocol

import io.github.sekimotosa.c2mirror.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class PacketFramerTest {
    private val names = listOf("cwpa-request1", "afmt-request", "cvrp-request", "clok-request", "time-request1",
        "asyn-feed", "asyn-sprp", "asyn-feed-nofdsc", "asyn-eat", "asyn-rels", "og-request")

    private fun stream(): Pair<ByteArray, List<ByteArray>> {
        val out = ByteArrayOutputStream()
        val bodies = ArrayList<ByteArray>()
        repeat(3) { for (n in names) { out.write(Fixtures.wire(n)); bodies += Fixtures.body(n) } }
        return out.toByteArray() to bodies
    }

    private fun collect(framer: (List<ByteArray>) -> Unit): MutableList<ByteArray> = mutableListOf()

    private fun run(chunker: (Int) -> Int): Pair<List<ByteArray>, PacketFramer> {
        val (data, _) = stream()
        val got = ArrayList<ByteArray>()
        val f = PacketFramer { b, o, l -> got += b.copyOfRange(o, o + l) }
        var p = 0
        while (p < data.size) {
            val n = minOf(chunker(p), data.size - p)
            f.feed(data, p, n)
            p += n
        }
        return got to f
    }

    private fun assertAll(got: List<ByteArray>) {
        val expected = stream().second
        assertEquals(expected.size, got.size)
        expected.indices.forEach { assertArrayEquals("packet $it", expected[it], got[it]) }
    }

    @Test fun wholeStreamAtOnce() = assertAll(run { Int.MAX_VALUE }.first)

    @Test fun byteByByte() = assertAll(run { 1 }.first)

    @Test fun usbSizedChunks() = assertAll(run { 16384 }.first)

    @Test fun randomChunks() {
        val rnd = Random(42)
        repeat(20) { assertAll(run { 1 + rnd.nextInt(70000) }.first) }
    }

    @Test fun resyncAfterGarbage() {
        val out = ByteArrayOutputStream()
        out.write(Fixtures.wire("clok-request"))
        out.write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f, 1, 2, 3, 4, 5, 6, 7, 8, 9)) // 破損データ
        out.write(Fixtures.wire("cwpa-request1"))
        val data = out.toByteArray()
        val got = ArrayList<ByteArray>()
        val f = PacketFramer { b, o, l -> got += b.copyOfRange(o, o + l) }
        f.feed(data, 0, data.size)
        assertEquals(2, got.size)
        assertArrayEquals(Fixtures.body("cwpa-request1"), got[1])
        assertEquals(13L, f.bytesDiscarded)
        assertTrue(f.resyncCount >= 1)
    }

    @Test fun absurdLengthIsRejected() {
        val bad = ByteArray(8)
        bad.putI32le(0, 0x7FFFFFFF); bad.putI32le(4, Magic.ASYN)
        val good = Fixtures.wire("asyn-need".let { "asyn-rels" })
        val got = ArrayList<ByteArray>()
        val f = PacketFramer { b, o, l -> got += b.copyOfRange(o, o + l) }
        f.feed(bad + good, 0, bad.size + good.size)
        assertEquals(1, got.size)
    }

    @Test(expected = ProtocolException::class)
    fun desyncLimitThrows() {
        val f = PacketFramer(maxResyncBytes = 1000) { _, _, _ -> }
        val junk = ByteArray(5000) { 0x55 }
        f.feed(junk, 0, junk.size)
    }

    @Test fun largePacketSplitAcrossManyReads() {
        val big = Fixtures.wire("asyn-feed")
        val got = ArrayList<ByteArray>()
        val f = PacketFramer { b, o, l -> got += b.copyOfRange(o, o + l) }
        var p = 0
        while (p < big.size) { val n = minOf(512, big.size - p); f.feed(big, p, n); p += n }
        assertArrayEquals(Fixtures.body("asyn-feed"), got.single())
        assertEquals(0, f.buffered)
    }
}

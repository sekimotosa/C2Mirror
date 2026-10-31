package io.github.sekimotosa.c2mirror.protocol

import io.github.sekimotosa.c2mirror.Fixtures
import io.github.sekimotosa.c2mirror.Fixtures.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 参考実装 packet/asyn_test.go 相当: 送信パケットがキャプチャと完全一致すること */
class PacketBuildersTest {
    @Test fun need() = assertArrayEquals(Fixtures.load("asyn-need"), Packets.need(0x0000000102c16ca0L))
    @Test fun hpd1() = assertArrayEquals(Fixtures.load("asyn-hpd1"), Packets.hpd1())
    @Test fun hpa1() = assertArrayEquals(Fixtures.load("asyn-hpa1"), Packets.hpa1(0x00000001145392F0L))
    @Test fun hpa0() = assertArrayEquals(Fixtures.load("asyn-hpa0"), Packets.hpa0(0x0000000102C5FC10L))
    @Test fun hpd0() = assertArrayEquals(Fixtures.load("asyn-hpd0"), Packets.hpd0())

    @Test fun ping() {
        assertEquals("10000000" + "676e6970" + "0000000001000000", Packets.ping().toHex())
    }

    @Test fun hpd1DictMatchesCapturedDict() =
        assertArrayEquals(Fixtures.load("coremedia/dict.bin"), Dicts.serialize(Packets.hpd1Dict()))

    @Test fun hpa1DictMatchesCapturedDict() =
        assertArrayEquals(Fixtures.load("coremedia/serialize_dict.bin"), Dicts.serialize(Packets.hpa1Dict()))

    @Test fun booleanDict() = assertArrayEquals(
        Fixtures.load("coremedia/bulvalue.bin"), Dicts.serialize(StringKeyDict(listOf("Valeria" to true))))

    @Test fun asbdFromHpaDict() =
        assertArrayEquals(Fixtures.load("coremedia/adsb-from-hpa-dict.bin"), AudioStreamBasicDescription.DEFAULT.serialize56())
}

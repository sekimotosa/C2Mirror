package io.github.sekimotosa.c2mirror.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iPhone の典型的な Descriptor 構成（参考実装・libusb ダンプを元にした模擬データ）での判定 */
class AppleUsbTest {
    private fun bulk(addr: Int) = EndpointInfo(addr, 0x02, 512)
    private val ptp = InterfaceInfo(0, 0, 0x06, 0x01, 0x01, "PTP", listOf(bulk(0x81), bulk(0x02), EndpointInfo(0x83, 0x03, 64)))
    private val mux = InterfaceInfo(1, 0, 0xFF, 0xFE, 0x02, "Apple USB Multiplexor", listOf(bulk(0x04), bulk(0x85)))
    private val qt = InterfaceInfo(2, 0, 0xFF, 0x2A, 0xFF, null, listOf(bulk(0x86), bulk(0x05)))

    private fun device(vararg configs: ConfigurationInfo, vendor: Int = 0x05AC) =
        UsbDeviceSnapshot("/dev/bus/usb/001/002", vendor, 0x12A8, "Apple Inc.", "iPhone", 0, configs.toList())

    private val c1 = ConfigurationInfo(1, "PTP", listOf(ptp))
    private val c4 = ConfigurationInfo(4, "PTP + Apple Mobile Device", listOf(ptp, mux))
    private val c5 = ConfigurationInfo(5, null, listOf(ptp, mux, qt))

    @Test fun beforeActivation() {
        val d = device(c1, c4)
        assertTrue(AppleUsb.isMirrorCapableDevice(d))
        assertNull(AppleUsb.findQuickTimeConfig(d))
        assertEquals(4, AppleUsb.findMuxConfig(d)!!.id)
    }

    @Test fun afterActivation() {
        val d = device(c1, c4, c5)
        val q = AppleUsb.findQuickTimeConfig(d)
        assertNotNull(q)
        assertEquals(5, q!!.id)
        val i = AppleUsb.findQuickTimeInterface(q)!!
        assertEquals(2, i.id)
        assertEquals(0x86, i.bulkIn!!.address)
        assertEquals(0x05, i.bulkOut!!.address)
        // QT 構成は usbmux インターフェースも含むが、mux 構成としては扱わない
        assertEquals(4, AppleUsb.findMuxConfig(d)!!.id)
        assertEquals(4, q.bulkEndpointCount - 2) // PTP の bulk 2本を除いた QT 構成の bulk 数（mux 2 + AV 2）
    }

    @Test fun qtInterfaceWithoutBothEndpointsIsRejected() {
        val broken = ConfigurationInfo(5, null, listOf(mux, qt.copy(endpoints = listOf(bulk(0x86)))))
        assertFalse(AppleUsb.isQuickTimeConfig(broken))
    }

    @Test fun nonAppleVendorIsNotCapable() {
        assertFalse(AppleUsb.isMirrorCapableDevice(device(c4, c5, vendor = 0x18D1)))
    }

    @Test fun appleDeviceWithoutMuxIsNotCapable() {
        assertFalse(AppleUsb.isMirrorCapableDevice(device(c1)))
    }

    @Test fun describeContainsNoSerial() {
        val text = AppleUsb.describe(device(c1, c4, c5))
        assertTrue(text.contains("05ac:12a8"))
        assertTrue(text.contains("[QuickTime]"))
        assertFalse(text.contains("serial", ignoreCase = true))
    }
}

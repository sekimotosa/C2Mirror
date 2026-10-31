package io.github.sekimotosa.c2mirror.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

/** UsbDevice → UsbDeviceSnapshot。getSerialNumber() は呼ばない（機密情報を扱わない）。 */
fun UsbDevice.snapshot(): UsbDeviceSnapshot {
    val configs = (0 until configurationCount).map { ci ->
        val c = getConfiguration(ci)
        ConfigurationInfo(c.id, c.name, (0 until c.interfaceCount).map { c.getInterface(it).info() })
    }
    return UsbDeviceSnapshot(
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
        manufacturerName = runCatching { manufacturerName }.getOrNull(),
        productName = runCatching { productName }.getOrNull(),
        deviceClass = deviceClass,
        configurations = configs,
    )
}

private fun UsbInterface.info() = InterfaceInfo(
    id, alternateSetting, interfaceClass, interfaceSubclass, interfaceProtocol, name,
    (0 until endpointCount).map { getEndpoint(it).let { e -> EndpointInfo(e.address, e.attributes, e.maxPacketSize, e.interval) } }
)

fun UsbDevice.findConfiguration(id: Int): UsbConfiguration? =
    (0 until configurationCount).map { getConfiguration(it) }.firstOrNull { it.id == id }

fun UsbConfiguration.findInterface(id: Int, alt: Int): UsbInterface? =
    (0 until interfaceCount).map { getInterface(it) }.firstOrNull { it.id == id && it.alternateSetting == alt }

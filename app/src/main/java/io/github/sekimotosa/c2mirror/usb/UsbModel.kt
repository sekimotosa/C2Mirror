package io.github.sekimotosa.c2mirror.usb

/**
 * Android の UsbDevice から取り出した Descriptor 情報（Android API に依存しない形で保持し、単体テスト可能にする）。
 * シリアル番号は意図的に保持しない。
 */
data class EndpointInfo(val address: Int, val attributes: Int, val maxPacketSize: Int, val interval: Int = 0) {
    val isIn get() = address and 0x80 != 0
    val isBulk get() = attributes and 0x03 == 0x02
    val number get() = address and 0x0F
    override fun toString() = "EP 0x%02x %s %s maxPacket=%d".format(
        address, if (isIn) "IN" else "OUT",
        when (attributes and 3) { 0 -> "CONTROL"; 1 -> "ISOC"; 2 -> "BULK"; else -> "INTERRUPT" }, maxPacketSize)
}

data class InterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val name: String?,
    val endpoints: List<EndpointInfo>,
) {
    val bulkIn get() = endpoints.firstOrNull { it.isBulk && it.isIn }
    val bulkOut get() = endpoints.firstOrNull { it.isBulk && !it.isIn }
    override fun toString() = "Interface #%d alt=%d class=0x%02x sub=0x%02x proto=0x%02x%s".format(
        id, alternateSetting, interfaceClass, interfaceSubclass, interfaceProtocol, name?.let { " \"$it\"" } ?: "")
}

data class ConfigurationInfo(val id: Int, val name: String?, val interfaces: List<InterfaceInfo>) {
    val bulkEndpointCount get() = interfaces.sumOf { i -> i.endpoints.count { it.isBulk } }
    override fun toString() = "Configuration #$id${name?.let { " \"$it\"" } ?: ""} (${interfaces.size} interfaces, $bulkEndpointCount bulk endpoints)"
}

data class UsbDeviceSnapshot(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val deviceClass: Int,
    val configurations: List<ConfigurationInfo>,
) {
    val isApple get() = vendorId == AppleUsb.VENDOR_ID
    val vidPid get() = "%04x:%04x".format(vendorId, productId)
}

/** iPhone / iPad の USB 構成判定（参考実装 discovery.go の findConfigurations 相当）。 */
object AppleUsb {
    const val VENDOR_ID = 0x05AC
    const val CLASS_VENDOR_SPECIFIC = 0xFF
    /** QuickTime 画面転送用インターフェースのサブクラス */
    const val SUBCLASS_QUICKTIME = 0x2A
    /** usbmux 用インターフェースのサブクラス */
    const val SUBCLASS_USBMUX = 0xFE

    /** 隠し QuickTime 構成を有効化するベンダーリクエスト（参考実装 sendQTConfigControlRequest） */
    const val QT_REQUEST_TYPE = 0x40
    const val QT_REQUEST = 0x52
    const val QT_VALUE = 0x0000
    const val QT_INDEX_ENABLE = 0x0002
    const val QT_INDEX_DISABLE = 0x0000

    fun findInterface(config: ConfigurationInfo, subclass: Int): InterfaceInfo? =
        config.interfaces.firstOrNull { it.interfaceClass == CLASS_VENDOR_SPECIFIC && it.interfaceSubclass == subclass }

    fun isQuickTimeConfig(c: ConfigurationInfo) = findQuickTimeInterface(c) != null

    /** QuickTime インターフェース: Class 0xFF / Subclass 0x2A かつ Bulk IN/OUT を持つもの */
    fun findQuickTimeInterface(c: ConfigurationInfo): InterfaceInfo? =
        c.interfaces.firstOrNull {
            it.interfaceClass == CLASS_VENDOR_SPECIFIC && it.interfaceSubclass == SUBCLASS_QUICKTIME &&
                it.bulkIn != null && it.bulkOut != null
        }

    fun isMuxConfig(c: ConfigurationInfo) = findInterface(c, SUBCLASS_USBMUX) != null && !isQuickTimeConfig(c)

    fun findQuickTimeConfig(d: UsbDeviceSnapshot): ConfigurationInfo? = d.configurations.lastOrNull { isQuickTimeConfig(it) }

    fun findMuxConfig(d: UsbDeviceSnapshot): ConfigurationInfo? = d.configurations.lastOrNull { isMuxConfig(it) }

    /** Vendor ID だけで判定せず、usbmux もしくは QuickTime 構成を持つことを確認する。 */
    fun isMirrorCapableDevice(d: UsbDeviceSnapshot) = d.isApple && (findMuxConfig(d) != null || findQuickTimeConfig(d) != null)

    fun describe(d: UsbDeviceSnapshot): String = buildString {
        appendLine("Device ${d.vidPid} ${d.manufacturerName ?: "?"} / ${d.productName ?: "?"} (${d.deviceName})")
        for (c in d.configurations) {
            val tags = listOfNotNull(if (isQuickTimeConfig(c)) "QuickTime" else null, if (isMuxConfig(c)) "usbmux" else null)
            appendLine("  $c${if (tags.isNotEmpty()) " [" + tags.joinToString() + "]" else ""}")
            for (i in c.interfaces) {
                appendLine("    $i")
                for (e in i.endpoints) appendLine("      $e")
            }
        }
    }
}

package io.github.sekimotosa.c2mirror.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * USB 機器の接続検出・切断検知・アクセス許可の取得。
 * 権限は Android 標準の UsbManager.requestPermission を使用する。
 */
class UsbDeviceManager(private val context: Context) {

    interface Listener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached(device: UsbDevice)
    }

    val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val permissionCallbacks = HashMap<String, MutableList<(Boolean) -> Unit>>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> listeners.forEach { it.onDeviceAttached(device) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> listeners.forEach { it.onDeviceDetached(device) }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    val cbs = synchronized(permissionCallbacks) { permissionCallbacks.remove(device.deviceName) }
                    cbs?.forEach { it(granted) }
                }
            }
        }
    }

    val isUsbHostSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun allDevices(): List<UsbDevice> = runCatching { usbManager.deviceList.values.toList() }.getOrDefault(emptyList())

    fun appleDevices(): List<UsbDevice> = allDevices().filter { it.vendorId == AppleUsb.VENDOR_ID }

    fun findDevice(deviceName: String): UsbDevice? = allDevices().firstOrNull { it.deviceName == deviceName }

    fun hasPermission(device: UsbDevice) = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)

    /** 権限ダイアログを表示する。結果はメインスレッドでコールバックされる。 */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (hasPermission(device)) { callback(true); return }
        val first = synchronized(permissionCallbacks) {
            val list = permissionCallbacks.getOrPut(device.deviceName) { mutableListOf() }
            list += callback
            list.size == 1
        }
        if (!first) return // 既にダイアログ表示中
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // Android 12+ ではシステムが EXTRA_DEVICE 等を追記するため MUTABLE が必要（パッケージ指定で明示化）
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    /** 権限待ちを破棄（セッション中断時） */
    fun cancelPendingPermission(device: UsbDevice) {
        synchronized(permissionCallbacks) { permissionCallbacks.remove(device.deviceName) }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "io.github.sekimotosa.c2mirror.USB_PERMISSION"

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}

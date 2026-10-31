package io.github.sekimotosa.c2mirror

import android.app.Application
import io.github.sekimotosa.c2mirror.diag.Diagnostics
import io.github.sekimotosa.c2mirror.session.MirrorSession
import io.github.sekimotosa.c2mirror.usb.UsbDeviceManager

/** セッションはプロセス単位で1つ。Activity の再生成（回転など）でも USB 接続を維持する。 */
class C2MirrorApp : Application() {
    lateinit var usb: UsbDeviceManager
        private set
    lateinit var session: MirrorSession
        private set

    override fun onCreate() {
        super.onCreate()
        usb = UsbDeviceManager(this).also { it.start() }
        val diag = Diagnostics("C2Mirror ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        session = MirrorSession(usb, diag)
        session.setAutoStart(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true))
    }

    fun setAutoStart(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
        session.setAutoStart(enabled)
    }

    fun isAutoStart() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    companion object {
        private const val PREFS = "c2mirror"
        private const val KEY_AUTO = "auto_start"
    }
}

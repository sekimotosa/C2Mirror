package io.github.sekimotosa.c2mirror

import android.app.Application
import io.github.sekimotosa.c2mirror.diag.Diagnostics
import io.github.sekimotosa.c2mirror.pairing.HostIdentity
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
        session = MirrorSession(usb, diag, ::hostIdentity)
        session.setAutoStart(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true))
    }

    fun setAutoStart(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
        session.setAutoStart(enabled)
    }

    private var identity: HostIdentity? = null

    /** ペアリング用の身元情報。初回のみ鍵を生成し、アプリ専用領域に保存する（外部へは送信しない）。 */
    @Synchronized
    fun hostIdentity(): HostIdentity {
        identity?.let { return it }
        val prefs = getSharedPreferences(PAIRING_PREFS, MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        val stored = (prefs.all as Map<String, Any?>).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val id = HostIdentity.deserialize(stored) ?: HostIdentity.generate().also { g ->
            prefs.edit().apply { g.serialize().forEach { (k, v) -> putString(k, v) } }.apply()
        }
        identity = id
        return id
    }

    fun isAutoStart() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    companion object {
        private const val PREFS = "c2mirror"
        private const val KEY_AUTO = "auto_start"
        private const val PAIRING_PREFS = "pairing_identity"
    }
}

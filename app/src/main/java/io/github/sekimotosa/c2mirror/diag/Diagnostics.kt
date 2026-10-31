package io.github.sekimotosa.c2mirror.diag

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 診断情報。シリアル番号・映像データ・認証情報は一切記録しない
 * （USB シリアルは取得すらしない。パケットは種別と長さのみ記録）。
 */
class Diagnostics(private val appVersion: String) {
    private val lock = Any()
    private val log = ArrayDeque<String>()
    private val start = System.currentTimeMillis()

    var usbHostSupported: Boolean? = null
    var deviceDetected: String? = null
    var deviceDescription: String? = null
    var permission: String? = null
    var qtActivation: String? = null
    var reenumeration: String? = null
    var activeConfiguration: String? = null
    var interfaceClaim: String? = null
    var sessionState: String? = null
    var pingReceived = false
    var clockNegotiation: String? = null
    var firstVideoPacketAt: Long? = null
    var videoFormat: String? = null
    var decoder: String? = null
    var firstFrameRenderedAt: Long? = null
    var lastError: String? = null
    var stats: () -> String = { "" }

    fun log(msg: String) {
        val line = "+%6.1fs %s".format((System.currentTimeMillis() - start) / 1000.0, msg)
        synchronized(lock) {
            log.addLast(line)
            while (log.size > MAX_LOG) log.removeFirst()
        }
        android.util.Log.i("C2M", msg)
    }

    fun error(msg: String) {
        lastError = msg
        log("ERROR: $msg")
    }

    fun resetSession() {
        pingReceived = false
        clockNegotiation = null
        firstVideoPacketAt = null
        firstFrameRenderedAt = null
    }

    fun export(): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        appendLine("=== C2Mirror 診断レポート ===")
        appendLine("作成日時: $now")
        appendLine("アプリ: $appVersion")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("[USB]")
        appendLine("USB Host 対応: ${usbHostSupported.fmt()}")
        appendLine("iPhone 検出: ${deviceDetected ?: "未検出"}")
        appendLine("USB アクセス許可: ${permission ?: "-"}")
        appendLine("QuickTime 構成の有効化: ${qtActivation ?: "-"}")
        appendLine("再認識: ${reenumeration ?: "-"}")
        appendLine("アクティブ構成: ${activeConfiguration ?: "-"}")
        appendLine("インターフェース Claim: ${interfaceClaim ?: "-"}")
        appendLine()
        appendLine("[Descriptor]")
        appendLine(deviceDescription ?: "(なし)")
        appendLine("[AV セッション]")
        appendLine("状態: ${sessionState ?: "-"}")
        appendLine("PING 受信: ${if (pingReceived) "あり" else "なし"}")
        appendLine("クロック交換: ${clockNegotiation ?: "-"}")
        appendLine("最初の映像パケット: ${firstVideoPacketAt?.let { "受信済み" } ?: "未受信"}")
        appendLine("映像フォーマット: ${videoFormat ?: "-"}")
        appendLine()
        appendLine("[MediaCodec]")
        appendLine("デコーダ: ${decoder ?: "-"}")
        appendLine("最初のフレーム描画: ${firstFrameRenderedAt?.let { "成功" } ?: "未描画"}")
        appendLine("統計: ${runCatching { stats() }.getOrDefault("")}")
        appendLine()
        appendLine("最後のエラー: ${lastError ?: "なし"}")
        appendLine()
        appendLine("[ログ]")
        synchronized(lock) { log.forEach { appendLine(it) } }
    }

    private fun Boolean?.fmt() = when (this) { true -> "対応"; false -> "非対応"; null -> "-" }

    companion object { const val MAX_LOG = 300 }
}

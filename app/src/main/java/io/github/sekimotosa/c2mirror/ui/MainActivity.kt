package io.github.sekimotosa.c2mirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sekimotosa.c2mirror.C2MirrorApp
import io.github.sekimotosa.c2mirror.R
import io.github.sekimotosa.c2mirror.session.ErrorKind
import io.github.sekimotosa.c2mirror.session.MirrorSession
import io.github.sekimotosa.c2mirror.session.Phase
import io.github.sekimotosa.c2mirror.session.UiState
import io.github.sekimotosa.c2mirror.usb.UsbDeviceManager.Companion.usbDevice

class MainActivity : Activity(), MirrorSession.StateObserver {

    private val app get() = application as C2MirrorApp
    private val session get() = app.session

    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var idlePanel: View
    private lateinit var streamOverlay: View
    private lateinit var tvUsbHost: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvError: TextView
    private lateinit var tvStream: TextView
    private lateinit var btnStart: Button
    private lateinit var swAuto: Switch

    private var last: UiState? = null
    private var pendingExport: String? = null

    private val hideOverlay = Runnable { streamOverlay.visibility = View.GONE }
    private val statsTick = object : Runnable {
        override fun run() {
            if (last?.phase == Phase.STREAMING) {
                val s = last
                tvStream.text = "${s?.device ?: "iPhone"}  ${s?.videoWidth}×${s?.videoHeight}  ${session.shortStats()}"
                tvStream.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        videoContainer = findViewById(R.id.videoContainer)
        surfaceView = findViewById(R.id.surface)
        idlePanel = findViewById(R.id.idlePanel)
        streamOverlay = findViewById(R.id.streamOverlay)
        tvUsbHost = findViewById(R.id.tvUsbHost)
        tvDevice = findViewById(R.id.tvDevice)
        tvPhase = findViewById(R.id.tvPhase)
        tvError = findViewById(R.id.tvError)
        tvStream = findViewById(R.id.tvStream)
        btnStart = findViewById(R.id.btnStart)
        swAuto = findViewById(R.id.swAuto)

        swAuto.isChecked = app.isAutoStart()
        swAuto.setOnCheckedChangeListener { _, checked -> app.setAutoStart(checked) }
        btnStart.setOnClickListener {
            if (last?.phase == Phase.IDLE) session.startManually() else session.stopManually()
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { session.stopManually() }
        findViewById<Button>(R.id.btnDiag).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.btnDiag2).setOnClickListener { showDiagnostics() }
        videoContainer.setOnClickListener { showOverlayTemporarily() }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = session.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = session.setSurface(null)
        })

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** USB_DEVICE_ATTACHED で起動・前面化された場合（プラグ&プレイ） */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDevice()?.let { session.onLaunchedForDevice(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addObserver(this)
    }

    override fun onStop() {
        session.removeObserver(this)
        super.onStop()
    }

    override fun onState(state: UiState) {
        last = state
        val streaming = state.phase == Phase.STREAMING
        tvUsbHost.text = if (state.usbHostSupported) "USB Host: 対応" else "USB Host: 非対応（この端末では利用できません）"
        tvDevice.text = when {
            state.device == null -> "iPhone: 未接続"
            state.deviceMirrorCapable -> "iPhone: 認識済み（${state.device}）"
            else -> "Apple 機器: ${state.device}（画面転送に対応した構成が見つかりません）"
        }
        tvPhase.text = phaseText(state)
        val err = state.error
        tvError.visibility = if (err != null) View.VISIBLE else View.GONE
        tvError.text = err?.let { "${errorTitle(it.kind)}\n${it.detail}" } ?: ""
        btnStart.text = getString(if (state.phase == Phase.IDLE) R.string.start else R.string.cancel)
        btnStart.isEnabled = state.usbHostSupported && state.phase != Phase.STOPPING

        videoContainer.setAspectRatio(state.videoWidth, state.videoHeight)
        idlePanel.visibility = if (streaming) View.GONE else View.VISIBLE
        setFullscreen(streaming)
        // 描画中（接続処理中も含む）は画面スリープを抑止
        if (state.phase != Phase.IDLE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (streaming) {
            if (streamOverlay.visibility != View.VISIBLE && tvStream.text.isNullOrEmpty()) showOverlayTemporarily()
            tvStream.removeCallbacks(statsTick); tvStream.post(statsTick)
        } else {
            streamOverlay.visibility = View.GONE
            tvStream.text = ""
        }
    }

    private fun phaseText(s: UiState): String = when (s.phase) {
        Phase.IDLE -> when {
            !s.usbHostSupported -> ""
            s.device == null -> "iPhone を USB-C ケーブルで接続してください"
            s.suppressedUntilReplug -> "停止中（「接続開始」または抜き差しで再開）"
            !s.autoStart -> "「接続開始」を押してください"
            else -> "待機中"
        }
        Phase.WAITING_PERMISSION -> "USB アクセスの許可を待っています…"
        Phase.ACTIVATING -> "iPhone の画面転送モードを有効化中…"
        Phase.WAITING_REENUMERATION -> "iPhone の再接続を待っています…"
        Phase.CONNECTING -> "USB 接続を確立中…"
        Phase.WAITING_PING -> "iPhone の応答を待っています…"
        Phase.NEGOTIATING -> "映像ストリームを開始しています…"
        Phase.STREAMING -> "ミラーリング中"
        Phase.STOPPING -> "切断中…"
    }

    private fun errorTitle(k: ErrorKind) = when (k) {
        ErrorKind.NO_USB_HOST -> "USB Host 非対応"
        ErrorKind.NO_DEVICE -> "iPhone 未接続"
        ErrorKind.PERMISSION_DENIED -> "USB 権限が拒否されました"
        ErrorKind.QT_ACTIVATION_FAILED -> "画面転送モードの有効化に失敗"
        ErrorKind.OPEN_FAILED -> "USB デバイスを開けません"
        ErrorKind.INTERFACE_CLAIM_FAILED -> "USB インターフェースの確保に失敗"
        ErrorKind.NO_PING -> "iPhone からの応答なし"
        ErrorKind.STREAM_START_FAILED -> "映像転送の開始に失敗"
        ErrorKind.DISCONNECTED -> "切断されました"
        ErrorKind.USB_IO_ERROR -> "USB 通信エラー"
        ErrorKind.PROTOCOL_ERROR -> "プロトコルエラー"
        ErrorKind.DECODER_ERROR -> "映像デコードエラー"
    }

    private fun showOverlayTemporarily() {
        if (last?.phase != Phase.STREAMING) return
        streamOverlay.visibility = View.VISIBLE
        streamOverlay.removeCallbacks(hideOverlay)
        streamOverlay.postDelayed(hideOverlay, 3000)
    }

    private fun setFullscreen(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showDiagnostics() {
        val text = session.diagnostics.export()
        val tv = TextView(this).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.share) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "C2Mirror diagnostics")
                    putExtra(Intent.EXTRA_TEXT, text)
                }, getString(R.string.share)))
            }
            .setNeutralButton(R.string.save) { _, _ ->
                pendingExport = text
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "c2mirror-diagnostics.txt")
                }, REQ_SAVE)
            }
            .setNegativeButton(R.string.copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("C2Mirror diagnostics", text))
                Toast.makeText(this, R.string.copy, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val text = pendingExport
        pendingExport = null
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK && uri != null && text != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }.onSuccess { Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "保存に失敗しました: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        private const val REQ_SAVE = 1001
    }
}

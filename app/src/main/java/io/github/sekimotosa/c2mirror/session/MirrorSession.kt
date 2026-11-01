package io.github.sekimotosa.c2mirror.session

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import io.github.sekimotosa.c2mirror.diag.Diagnostics
import io.github.sekimotosa.c2mirror.protocol.CMSampleBuffer
import io.github.sekimotosa.c2mirror.protocol.PacketFramer
import io.github.sekimotosa.c2mirror.protocol.ProtocolEvent
import io.github.sekimotosa.c2mirror.protocol.ProtocolException
import io.github.sekimotosa.c2mirror.protocol.ProtocolListener
import io.github.sekimotosa.c2mirror.protocol.QuickTimeProtocol
import io.github.sekimotosa.c2mirror.pairing.HostIdentity
import io.github.sekimotosa.c2mirror.pairing.PairingAgent
import io.github.sekimotosa.c2mirror.pairing.UsbMux
import io.github.sekimotosa.c2mirror.usb.AppleUsb
import io.github.sekimotosa.c2mirror.usb.MuxUsbLink
import io.github.sekimotosa.c2mirror.usb.UsbDeviceManager
import io.github.sekimotosa.c2mirror.usb.UsbDeviceSnapshot
import io.github.sekimotosa.c2mirror.usb.UsbTransport
import io.github.sekimotosa.c2mirror.usb.findConfiguration
import io.github.sekimotosa.c2mirror.usb.findInterface
import io.github.sekimotosa.c2mirror.usb.snapshot
import io.github.sekimotosa.c2mirror.video.VideoDecoder
import io.github.sekimotosa.c2mirror.video.VideoFormat
import io.github.sekimotosa.c2mirror.video.VideoStreamProcessor
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase {
    IDLE,
    WAITING_PERMISSION,
    ACTIVATING,
    WAITING_REENUMERATION,
    CONNECTING,
    WAITING_PING,
    NEGOTIATING,
    STREAMING,
    STOPPING,
}

enum class ErrorKind {
    NO_USB_HOST,
    NO_DEVICE,
    PERMISSION_DENIED,
    QT_ACTIVATION_FAILED,
    OPEN_FAILED,
    INTERFACE_CLAIM_FAILED,
    NO_PING,
    STREAM_START_FAILED,
    DISCONNECTED,
    USB_IO_ERROR,
    PROTOCOL_ERROR,
    DECODER_ERROR,
    PAIRING_FAILED,
}

data class SessionError(val kind: ErrorKind, val detail: String)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val usbHostSupported: Boolean = true,
    /** 接続中の Apple 機器（null = 未接続） */
    val device: String? = null,
    val deviceMirrorCapable: Boolean = false,
    val error: SessionError? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val autoStart: Boolean = true,
    val suppressedUntilReplug: Boolean = false,
    /** ペアリング（信頼）の状況。iPhone 側の操作が必要な場合の案内に使う */
    val pairing: PairingAgent.State? = null,
)

/**
 * 接続状態と処理ライフサイクルの管理。
 *
 * スレッド構成:
 *  - 制御スレッド(c2m-session): 状態遷移・USB 構成切替・タイムアウト
 *  - 受信スレッド(c2m-usb-rx): Bulk IN → パケット分割 → プロトコル処理 → デコーダ投入（同一スレッドで遅延最小化）
 *  - デコーダスレッド(c2m-decoder): MediaCodec のコールバック
 *  UI スレッドでは一切ブロッキング処理を行わない。
 *
 * プラグ&プレイ: アプリ起動中に iPhone が接続されると自動的に
 *  権限要求 → QuickTime 構成有効化 → 再認識待ち → 接続 → ミラーリング開始 まで進む。
 */
class MirrorSession(
    private val usb: UsbDeviceManager,
    val diagnostics: Diagnostics,
    /** ペアリング用のホスト証明書（初回に生成して端末内に保存） */
    private val hostIdentity: () -> HostIdentity,
) : UsbDeviceManager.Listener {

    fun interface StateObserver { fun onState(state: UiState) }

    private val main = Handler(Looper.getMainLooper())
    private val ctlThread = HandlerThread("c2m-session").apply { start() }
    private val ctl = Handler(ctlThread.looper)
    private val observers = CopyOnWriteArrayList<StateObserver>()

    @Volatile var state = UiState(usbHostSupported = usb.isUsbHostSupported); private set

    // ---- 制御スレッドのみで触る ----
    private var target: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var transport: UsbTransport? = null
    private var protocol: QuickTimeProtocol? = null
    @Volatile private var decoder: VideoDecoder? = null
    private var processor: VideoStreamProcessor? = null
    private var reader: Thread? = null
    private var muxLink: MuxUsbLink? = null
    private var mux: UsbMux? = null
    private var pairingThread: Thread? = null
    @Volatile private var pairingState: PairingAgent.State? = null
    /** ペアリング完了後も映像が始まらない場合の自動再接続（1 回のみ） */
    private var restartAfterPairing = 0
    private var forceAutoStartOnce = false
    private var reenumDeadline = 0L
    private var activationAttempts = 0
    private var muxConfigId: Int? = null
    private var sessionToken = 0
    private val surfaceLock = Any()
    private var surface: Surface? = null // surfaceLock で保護

    init {
        diagnostics.usbHostSupported = usb.isUsbHostSupported
        diagnostics.stats = { statsLine() }
        usb.addListener(this)
        ctl.post { refreshDevice(); scheduleScan() }
    }

    // ================= 公開 API（どのスレッドからでも可） =================

    fun addObserver(o: StateObserver) { observers += o; main.post { o.onState(state) } }
    fun removeObserver(o: StateObserver) { observers -= o }

    fun setAutoStart(enabled: Boolean) = ctl.post {
        update { it.copy(autoStart = enabled) }
        if (enabled) tryAutoStart()
    }

    /** 「接続開始」ボタン */
    fun startManually() = ctl.post {
        update { it.copy(suppressedUntilReplug = false, error = null) }
        if (state.phase != Phase.IDLE) return@post
        val d = pickDevice()
        if (d == null) {
            fail(ErrorKind.NO_DEVICE, "iPhone が見つかりません。USB-C ケーブルで接続してください。", suppress = false)
            return@post
        }
        begin(d)
    }

    /** 「切断」ボタン */
    fun stopManually() = ctl.post {
        if (state.phase == Phase.IDLE) return@post
        diagnostics.log("user requested stop")
        teardown(graceful = true, restoreUsbmux = true)
        update { it.copy(phase = Phase.IDLE, error = null, suppressedUntilReplug = true, videoWidth = 0, videoHeight = 0) }
    }

    /** USB_DEVICE_ATTACHED インテントでアクティビティが起動された場合（権限は付与済み） */
    fun onLaunchedForDevice(device: UsbDevice) = onDeviceAttached(device)

    /** UI スレッドから。破棄時(null)はデコーダの出力切替完了まで最大 500ms 待つ。 */
    fun setSurface(s: Surface?) {
        val d = synchronized(surfaceLock) {
            surface = s
            decoder
        }
        d?.setSurface(s, waitMs = if (s == null) 500 else 0)
    }

    // ================= USB イベント =================

    override fun onDeviceAttached(device: UsbDevice) {
        ctl.post {
            if (device.vendorId != AppleUsb.VENDOR_ID) return@post
            diagnostics.log("attached ${"%04x:%04x".format(device.vendorId, device.productId)} ${device.deviceName}")
            ctl.removeCallbacks(clearSuppression)
            refreshDevice()
            when (state.phase) {
                Phase.WAITING_REENUMERATION -> checkReenumerated()
                Phase.IDLE -> tryAutoStart()
                // 「このデバイスが接続されたら C2Mirror を開く」経由で権限が付与された場合はダイアログを待たずに続行
                Phase.WAITING_PERMISSION -> if (target?.deviceName == device.deviceName && usb.hasPermission(device)) {
                    diagnostics.permission = "許可（既定アプリ設定）"
                    proceed(device)
                }
                else -> Unit
            }
        }
    }

    override fun onDeviceDetached(device: UsbDevice) {
        ctl.post {
            if (device.vendorId != AppleUsb.VENDOR_ID) return@post
            diagnostics.log("detached ${device.deviceName}")
            refreshDevice()
            val ours = target?.deviceName == device.deviceName
            when (state.phase) {
                Phase.WAITING_REENUMERATION, Phase.STOPPING -> Unit // 構成切替による再認識は想定内
                Phase.IDLE -> Unit
                else -> if (ours) {
                    teardown(graceful = false, restoreUsbmux = false)
                    fail(ErrorKind.DISCONNECTED, "iPhone が切断されました。", suppress = false)
                }
            }
            if (usb.appleDevices().isEmpty()) {
                // 物理的に抜かれた（数秒間戻ってこない）場合に自動開始の抑止を解除する
                ctl.removeCallbacks(clearSuppression)
                ctl.postDelayed(clearSuppression, REPLUG_DEBOUNCE_MS)
            }
        }
    }

    private val clearSuppression = Runnable {
        if (usb.appleDevices().isEmpty() && state.suppressedUntilReplug) {
            diagnostics.log("device unplugged; auto start re-armed")
            update { it.copy(suppressedUntilReplug = false) }
        }
        if (usb.appleDevices().isEmpty()) {
            restartAfterPairing = 0
            forceAutoStartOnce = false
        }
    }

    // ================= 状態遷移（制御スレッド） =================

    private fun pickDevice(): UsbDevice? {
        val apple = usb.appleDevices()
        return apple.firstOrNull { AppleUsb.findQuickTimeConfig(it.snapshot()) != null }
            ?: apple.firstOrNull { AppleUsb.isMirrorCapableDevice(it.snapshot()) }
            ?: apple.firstOrNull()
    }

    private fun refreshDevice() {
        val d = pickDevice()
        val snap = d?.snapshot()
        diagnostics.deviceDetected = snap?.let { "${it.vidPid} ${it.productName ?: ""}".trim() }
        if (snap != null) diagnostics.deviceDescription = AppleUsb.describe(snap)
        update { it.copy(device = snap?.let { s -> s.productName ?: "Apple ${s.vidPid}" },
            deviceMirrorCapable = snap?.let { s -> AppleUsb.isMirrorCapableDevice(s) } ?: false) }
    }

    /** 取りこぼした接続ブロードキャストを補うための低頻度スキャン */
    private fun scheduleScan() {
        ctl.postDelayed({
            if (state.phase == Phase.IDLE) { refreshDevice(); tryAutoStart() }
            scheduleScan()
        }, SCAN_INTERVAL_MS)
    }

    private fun tryAutoStart() {
        val s = state
        if (s.phase != Phase.IDLE) return
        if (forceAutoStartOnce) {
            if (pickDevice() == null) return
            forceAutoStartOnce = false
        } else if (!s.autoStart || s.suppressedUntilReplug) return
        if (!usb.isUsbHostSupported) { fail(ErrorKind.NO_USB_HOST, "この端末は USB Host に対応していません。"); return }
        val d = pickDevice() ?: return
        diagnostics.log("auto start for ${d.deviceName}")
        begin(d)
    }

    private fun begin(device: UsbDevice) {
        if (!usb.isUsbHostSupported) { fail(ErrorKind.NO_USB_HOST, "この端末は USB Host に対応していません。"); return }
        diagnostics.resetSession()
        activationAttempts = 0
        target = device
        update { it.copy(error = null) }
        withPermission(device) { proceed(device) }
    }

    private fun withPermission(device: UsbDevice, then: () -> Unit) {
        if (usb.hasPermission(device)) {
            diagnostics.permission = "許可済み"
            then(); return
        }
        update { it.copy(phase = Phase.WAITING_PERMISSION) }
        diagnostics.permission = "要求中"
        diagnostics.log("requesting USB permission for ${device.deviceName}")
        val token = sessionToken
        main.post {
            usb.requestPermission(device) { granted ->
                ctl.post {
                    if (token != sessionToken || state.phase != Phase.WAITING_PERMISSION) return@post
                    if (granted) {
                        diagnostics.permission = "許可"
                        then()
                    } else {
                        diagnostics.permission = "拒否"
                        fail(ErrorKind.PERMISSION_DENIED, "USB アクセスが許可されませんでした。")
                    }
                }
            }
        }
    }

    private fun proceed(device: UsbDevice) {
        val snap = device.snapshot()
        diagnostics.deviceDescription = AppleUsb.describe(snap)
        AppleUsb.findMuxConfig(snap)?.let { muxConfigId = it.id }
        val qt = AppleUsb.findQuickTimeConfig(snap)
        if (qt != null) {
            diagnostics.log("QuickTime configuration #${qt.id} present")
            openStreaming(device, snap)
        } else {
            activate(device, snap)
        }
    }

    /** 隠し QuickTime 構成の有効化（制御転送 0x40/0x52/0x0000/0x0002）→ 再認識待ち */
    private fun activate(device: UsbDevice, snap: UsbDeviceSnapshot) {
        if (!AppleUsb.isMirrorCapableDevice(snap)) {
            fail(ErrorKind.NO_DEVICE, "接続された Apple 機器に usbmux 構成が見つかりません（iPhone/iPad ではない可能性）。")
            return
        }
        update { it.copy(phase = Phase.ACTIVATING) }
        activationAttempts++
        val conn = usb.usbManager.openDevice(device)
        if (conn == null) { fail(ErrorKind.OPEN_FAILED, "USB デバイスを開けませんでした。"); return }
        val r = conn.controlTransfer(AppleUsb.QT_REQUEST_TYPE, AppleUsb.QT_REQUEST, AppleUsb.QT_VALUE,
            AppleUsb.QT_INDEX_ENABLE, null, 0, 1000)
        conn.close()
        // 参考実装ではこの転送はエラーを返すことがあるが、多くの場合それでも有効化される
        diagnostics.qtActivation = "制御転送 rc=$r (試行 $activationAttempts)"
        diagnostics.log("QT enable control transfer rc=$r")
        update { it.copy(phase = Phase.WAITING_REENUMERATION) }
        reenumDeadline = SystemClock.uptimeMillis() + REENUM_TIMEOUT_MS
        val token = sessionToken
        pollReenumeration(token)
    }

    private fun pollReenumeration(token: Int) {
        ctl.postDelayed({
            if (token == sessionToken && state.phase == Phase.WAITING_REENUMERATION) {
                checkReenumerated()
                if (state.phase == Phase.WAITING_REENUMERATION) pollReenumeration(token)
            }
        }, REENUM_POLL_MS)
    }

    private fun checkReenumerated() {
        val d = usb.appleDevices().firstOrNull { AppleUsb.findQuickTimeConfig(it.snapshot()) != null }
        if (d != null) {
            diagnostics.reenumeration = "成功（新しい構成を検出: ${d.deviceName}）"
            diagnostics.log("re-enumerated with QuickTime config: ${d.deviceName}")
            target = d
            // 再認識後は別の UsbDevice になるため、権限を再確認する
            withPermission(d) { proceed(d) }
            return
        }
        if (SystemClock.uptimeMillis() > reenumDeadline) {
            val still = usb.appleDevices().firstOrNull()
            if (still != null && activationAttempts < MAX_ACTIVATION_ATTEMPTS) {
                diagnostics.log("no re-enumeration; retrying activation")
                target = still
                withPermission(still) { activate(still, still.snapshot()) }
            } else {
                diagnostics.reenumeration = "タイムアウト"
                fail(ErrorKind.QT_ACTIVATION_FAILED,
                    "iPhone の画面転送モードを有効にできませんでした。iPhone のロックを解除し、「このコンピュータを信頼」が表示されたら許可して再接続してください。")
            }
        }
    }

    private fun openStreaming(device: UsbDevice, snap: UsbDeviceSnapshot) {
        update { it.copy(phase = Phase.CONNECTING) }
        val qtInfo = AppleUsb.findQuickTimeConfig(snap)!!
        val ifInfo = AppleUsb.findQuickTimeInterface(qtInfo)!!
        val conn = usb.usbManager.openDevice(device)
        if (conn == null) { fail(ErrorKind.OPEN_FAILED, "USB デバイスを開けませんでした。"); return }
        connection = conn

        val active = readActiveConfiguration(conn)
        var setResult = "skip"
        if (active != qtInfo.id) {
            val cfg = device.findConfiguration(qtInfo.id)
            setResult = if (cfg != null) runCatching { conn.setConfiguration(cfg) }.getOrDefault(false).toString() else "config not found"
        }
        val activeAfter = readActiveConfiguration(conn)
        diagnostics.activeConfiguration = "before=${active ?: "?"} QT=#${qtInfo.id} setConfiguration=$setResult after=${activeAfter ?: "?"}"
        diagnostics.log("configuration: ${diagnostics.activeConfiguration}")

        val iface = device.findConfiguration(qtInfo.id)?.findInterface(ifInfo.id, ifInfo.alternateSetting)
        val inEp = iface?.let { UsbTransport.findBulk(it, true) }
        val outEp = iface?.let { UsbTransport.findBulk(it, false) }
        if (iface == null || inEp == null || outEp == null) {
            fail(ErrorKind.INTERFACE_CLAIM_FAILED, "QuickTime インターフェースの Bulk エンドポイントが見つかりません。")
            return
        }
        val t = UsbTransport(conn, iface, inEp, outEp)
        val claim = t.claim()
        diagnostics.interfaceClaim = "Interface #${iface.id}: ${claim.detail} IN=0x%02x OUT=0x%02x".format(inEp.address, outEp.address)
        diagnostics.log("claim: ${diagnostics.interfaceClaim}")
        if (!claim.ok) {
            fail(ErrorKind.INTERFACE_CLAIM_FAILED, "AV 転送用インターフェースを確保できませんでした。")
            return
        }
        transport = t
        startReader(t)
        startPairing(conn, device, qtInfo)
    }

    /**
     * 同じ構成内の usbmux インターフェースで lockdown に接続し、ペアリング（信頼）とセッション確立を行う。
     * iPhone は信頼されていないホストへは PING 以降の画面転送を開始しないと考えられるため（実機検証 2 回目の所見）。
     */
    private fun startPairing(conn: UsbDeviceConnection, device: UsbDevice, qtInfo: io.github.sekimotosa.c2mirror.usb.ConfigurationInfo) {
        val token = sessionToken
        val muxInfo = AppleUsb.findInterface(qtInfo, AppleUsb.SUBCLASS_USBMUX)
        val iface = muxInfo?.let { device.findConfiguration(qtInfo.id)?.findInterface(it.id, it.alternateSetting) }
        val inEp = iface?.let { UsbTransport.findBulk(it, true) }
        val outEp = iface?.let { UsbTransport.findBulk(it, false) }
        if (iface == null || inEp == null || outEp == null) {
            diagnostics.pairing = "usbmux インターフェースなし"
            return
        }
        val link = MuxUsbLink(conn, iface, inEp, outEp)
        if (!link.claim()) {
            diagnostics.pairing = "usbmux インターフェースの確保に失敗"
            diagnostics.log("pairing: claim usbmux interface #${iface.id} failed")
            return
        }
        val m = UsbMux({ link.write(it) }, { diagnostics.log(it) })
        muxLink = link
        mux = m
        link.start({ b, o, l -> m.onUsbData(b, o, l) }, { e -> diagnostics.log("usbmux rx error: ${e.message}") })
        val agent = PairingAgent(
            mux = m,
            identity = hostIdentity,
            log = { diagnostics.log(it) },
            onState = { st, detail -> ctl.post { onPairingState(token, st, detail) } },
            isCancelled = { token != sessionToken },
        )
        pairingState = PairingAgent.State.CONNECTING
        pairingThread = Thread({ agent.run() }, "c2m-pairing").apply { start() }
    }

    private fun onPairingState(token: Int, st: PairingAgent.State, detail: String?) {
        if (token != sessionToken) return
        pairingState = st
        diagnostics.pairing = when (st) {
            PairingAgent.State.CONNECTING -> "lockdown 接続中"
            PairingAgent.State.WAITING_USER_TRUST -> "iPhone の「信頼」待ち"
            PairingAgent.State.LOCKED -> "iPhone がロック中（パスコードで解除が必要）"
            PairingAgent.State.PAIRED -> "ペアリング成功"
            PairingAgent.State.SESSION_OK -> "信頼済み・セッション確立（iOS ${detail ?: "?"}）"
            PairingAgent.State.DENIED -> "iPhone で「信頼しない」が選択された"
            PairingAgent.State.FAILED -> "失敗: ${detail ?: "?"}"
        }
        diagnostics.log("pairing: ${diagnostics.pairing}")
        update { it.copy(pairing = st) }
        when (st) {
            PairingAgent.State.DENIED -> {
                teardown(graceful = false, restoreUsbmux = true)
                fail(ErrorKind.PAIRING_FAILED, "iPhone で「信頼しない」が選択されました。ケーブルを抜き差しして、「信頼」をタップしてください。")
            }
            PairingAgent.State.SESSION_OK -> {
                // 信頼済みになっても映像が始まらなければ、構成を戻して一度だけ自動で再接続する
                ctl.postDelayed({
                    if (token == sessionToken && state.phase in setOf(Phase.WAITING_PING, Phase.NEGOTIATING) &&
                        restartAfterPairing < 1) {
                        restartAfterPairing++
                        diagnostics.log("trusted but no stream yet; restarting QuickTime session once")
                        forceAutoStartOnce = true
                        teardown(graceful = false, restoreUsbmux = true)
                    }
                }, RESTART_AFTER_PAIRING_MS)
            }
            else -> Unit
        }
    }

    private val pairingBusy get() = pairingState in setOf(
        PairingAgent.State.CONNECTING, PairingAgent.State.WAITING_USER_TRUST,
        PairingAgent.State.LOCKED, PairingAgent.State.PAIRED,
    )

    private fun readActiveConfiguration(conn: UsbDeviceConnection): Int? {
        val b = ByteArray(1)
        // GET_CONFIGURATION
        val n = conn.controlTransfer(0x80, 0x08, 0, 0, b, 1, 500)
        return if (n == 1) b[0].toInt() and 0xFF else null
    }

    private fun startReader(t: UsbTransport) {
        val token = ++sessionToken
        val dec = VideoDecoder(decoderCallbacks(token))
        synchronized(surfaceLock) {
            decoder = dec
            dec.setSurface(surface)
        }
        val proc = VideoStreamProcessor(dec)
        processor = proc
        var firstVideo = true
        val proto = QuickTimeProtocol(t, object : ProtocolListener {
            override fun onVideoSample(sample: CMSampleBuffer) {
                if (firstVideo) {
                    firstVideo = false
                    diagnostics.firstVideoPacketAt = SystemClock.uptimeMillis()
                    diagnostics.log("first FEED received")
                    ctl.post { restartAfterPairing = 0 }
                    ctl.post { if (token == sessionToken) update { it.copy(phase = Phase.STREAMING) } }
                }
                proc.onSample(sample)
            }

            override fun onEvent(event: ProtocolEvent) = onProtocolEvent(token, event, proc)
        })
        protocol = proto
        update { it.copy(phase = Phase.WAITING_PING) }
        diagnostics.sessionState = "PING 待ち"

        val framer = PacketFramer { buf, off, len ->
            try {
                proto.handlePacket(buf, off, len)
            } catch (e: ProtocolException) {
                diagnostics.log("packet error: ${e.message}")
            }
        }
        val devName = target?.deviceName
        reader = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                .onFailure { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } }
            var err: Throwable? = null
            try {
                t.readLoop(
                    isDevicePresent = { devName != null && usb.findDevice(devName) != null },
                    log = { diagnostics.log(it) },
                ) { b, o, l -> framer.feed(b, o, l) }
            } catch (e: Throwable) {
                err = e
            }
            ctl.post { onReaderExit(token, err) }
        }, "c2m-usb-rx").apply { start() }

        val started = SystemClock.uptimeMillis()
        lateinit var check: Runnable
        check = Runnable {
            if (token != sessionToken) return@Runnable
            val elapsed = SystemClock.uptimeMillis() - started
            // ペアリング（iPhone での「信頼」操作待ち）の間はタイムアウトを延長する
            if (pairingBusy && elapsed < PAIRING_WAIT_MAX_MS) { ctl.postDelayed(check, 1000); return@Runnable }
            when {
                state.phase == Phase.WAITING_PING && elapsed >= PING_TIMEOUT_MS -> {
                    teardown(graceful = false, restoreUsbmux = true)
                    fail(ErrorKind.NO_PING, "iPhone から応答（PING）がありません。iPhone のロックを解除して再接続してください。")
                }
                state.phase in setOf(Phase.WAITING_PING, Phase.NEGOTIATING) && elapsed >= STREAM_START_TIMEOUT_MS -> {
                    teardown(graceful = true, restoreUsbmux = true)
                    fail(ErrorKind.STREAM_START_FAILED, "映像転送を開始できませんでした（映像パケット未受信）。")
                }
                state.phase in setOf(Phase.WAITING_PING, Phase.NEGOTIATING) -> ctl.postDelayed(check, 1000)
            }
        }
        ctl.postDelayed(check, 1000)
    }

    private fun onProtocolEvent(token: Int, e: ProtocolEvent, proc: VideoStreamProcessor) {
        when (e) {
            is ProtocolEvent.PingReceived -> {
                diagnostics.pingReceived = true
                diagnostics.log("PING received")
                ctl.post { if (token == sessionToken && state.phase == Phase.WAITING_PING) update { it.copy(phase = Phase.NEGOTIATING) } }
                diagnostics.sessionState = "ネゴシエーション中"
            }
            is ProtocolEvent.AudioClockNegotiated -> { diagnostics.clockNegotiation = "CWPA 完了"; diagnostics.log("CWPA handled") }
            is ProtocolEvent.VideoClockNegotiated -> {
                diagnostics.clockNegotiation = "CWPA/CVRP 完了"
                diagnostics.log("CVRP handled; format=${e.format}")
                diagnostics.sessionState = "映像ストリーム要求済み (NEED)"
                // CVRP 内のフォーマットで先にデコーダを準備して最初のフレームまでの時間を短縮
                e.format?.let { proc.onFormatDescription(it) }
            }
            is ProtocolEvent.ClockSynced -> { diagnostics.clockNegotiation = "CWPA/CVRP/CLOK 完了"; diagnostics.log("CLOK handled") }
            is ProtocolEvent.StopRequested -> diagnostics.log("device sent STOP")
            is ProtocolEvent.Released -> diagnostics.log("device sent RELS")
            is ProtocolEvent.Warning -> diagnostics.log("warning: ${e.message}")
        }
    }

    private fun decoderCallbacks(token: Int) = object : VideoDecoder.Callbacks {
        override fun onVideoSizeChanged(width: Int, height: Int) {
            ctl.post { if (token == sessionToken) update { it.copy(videoWidth = width, videoHeight = height) } }
        }

        override fun onFirstFrameRendered(decoderName: String) {
            diagnostics.firstFrameRenderedAt = SystemClock.uptimeMillis()
            diagnostics.sessionState = "ミラーリング中"
            diagnostics.log("first frame rendered by $decoderName")
        }

        override fun onDecoderConfigured(decoderName: String, format: VideoFormat, lowLatency: Boolean) {
            diagnostics.decoder = "$decoderName lowLatency=$lowLatency"
            diagnostics.videoFormat = format.toString()
            diagnostics.log("decoder configured: $decoderName ($format) lowLatency=$lowLatency")
        }

        override fun onDecoderError(message: String) {
            diagnostics.error("decoder: $message")
            ctl.post {
                if (token == sessionToken && state.phase !in setOf(Phase.IDLE, Phase.STOPPING)) {
                    update { it.copy(error = SessionError(ErrorKind.DECODER_ERROR, "映像のデコードに失敗しました: $message")) }
                }
            }
        }
    }

    private fun onReaderExit(token: Int, err: Throwable?) {
        if (token != sessionToken) return
        if (state.phase == Phase.STOPPING || state.phase == Phase.IDLE) return
        val stillPresent = target?.let { usb.findDevice(it.deviceName) } != null
        teardown(graceful = false, restoreUsbmux = stillPresent)
        if (!stillPresent) {
            fail(ErrorKind.DISCONNECTED, "iPhone が切断されました。", suppress = false)
        } else if (err is ProtocolException) {
            fail(ErrorKind.PROTOCOL_ERROR, "通信データの解析に失敗しました: ${err.message}")
        } else {
            fail(ErrorKind.USB_IO_ERROR, "USB 通信エラー: ${err?.message ?: "受信停止"}")
        }
    }

    /** 全リソース解放。graceful=true ならデバイスへ停止要求を送ってから解放する。 */
    private fun teardown(graceful: Boolean, restoreUsbmux: Boolean) {
        val prevPhase = state.phase
        sessionToken++
        update { it.copy(phase = Phase.STOPPING) }
        target?.let { usb.cancelPendingPermission(it) }
        val t = transport
        val p = protocol
        if (graceful && t != null && p != null && prevPhase in setOf(Phase.NEGOTIATING, Phase.STREAMING)) {
            val ok = runCatching { p.closeSession(CLOSE_TIMEOUT_MS) }.getOrDefault(false)
            diagnostics.log("closeSession released=$ok")
        }
        t?.stop()
        reader?.join(1500)
        reader = null
        mux?.closeAll()
        mux = null
        muxLink?.close()
        muxLink = null
        pairingThread?.join(1000)
        pairingThread = null
        pairingState = null
        t?.close()
        transport = null
        protocol = null
        processor = null
        synchronized(surfaceLock) {
            decoder?.release()
            decoder = null
        }
        connection?.let { conn ->
            if (restoreUsbmux) {
                // QuickTime 構成を無効化して通常（usbmux）構成へ戻す（参考実装 StartReading 終了処理）
                runCatching {
                    conn.controlTransfer(AppleUsb.QT_REQUEST_TYPE, AppleUsb.QT_REQUEST, AppleUsb.QT_VALUE,
                        AppleUsb.QT_INDEX_DISABLE, null, 0, 500)
                }
                val mux = muxConfigId?.let { id -> target?.findConfiguration(id) }
                if (mux != null) runCatching { conn.setConfiguration(mux) }
            }
            conn.close()
        }
        connection = null
        diagnostics.sessionState = "停止"
        update { it.copy(phase = Phase.IDLE, pairing = null) }
    }

    private fun fail(kind: ErrorKind, detail: String, suppress: Boolean = true) {
        diagnostics.error("$kind: $detail")
        if (state.phase != Phase.IDLE && state.phase != Phase.STOPPING) {
            // 確立途中のリソースを解放
            teardown(graceful = false, restoreUsbmux = false)
        }
        // エラー後は自動再試行でループしないよう、抜き差しまたは「接続開始」まで自動開始を止める
        update { it.copy(phase = Phase.IDLE, error = SessionError(kind, detail),
            suppressedUntilReplug = it.suppressedUntilReplug || suppress, videoWidth = 0, videoHeight = 0) }
    }

    private fun update(f: (UiState) -> UiState) {
        val n = f(state)
        if (n == state) return
        state = n
        main.post { observers.forEach { it.onState(n) } }
    }

    private fun statsLine(): String {
        val d = decoder
        val p = protocol
        val t = transport
        return buildString {
            append("FEED=${p?.videoSamples ?: 0} EAT=${p?.audioSamples ?: 0} feedErr=${p?.feedParseErrors ?: 0} unknown=${p?.unknownPackets ?: 0}")
            append(" queued=${d?.framesQueued ?: 0} rendered=${d?.framesRendered ?: 0} dropped=${d?.framesDropped ?: 0}")
            append(" backlogFlush=${d?.backlogFlushes ?: 0} decodeLatency=%.1fms".format(d?.decodeLatencyMs ?: 0.0))
            append(" rx=${t?.bytesRead ?: 0}B tx=${t?.bytesWritten ?: 0}B rxMode=${t?.rxMode ?: "-"}")
            mux?.let { append(" mux in/out=${it.packetsIn}/${it.packetsOut}") }
            d?.lastError?.let { append(" decoderLastError=$it") }
        }
    }

    /** UI のオーバーレイ表示用の簡易統計 */
    fun shortStats(): String {
        val d = decoder ?: return ""
        return "%d fps表示 / デコード %.0f ms".format(fpsMeter.update(d.framesRendered), d.decodeLatencyMs)
    }

    private val fpsMeter = object {
        var lastFrames = 0L
        var lastTime = SystemClock.uptimeMillis()
        var fps = 0
        fun update(frames: Long): Int {
            val now = SystemClock.uptimeMillis()
            if (now - lastTime >= 1000) {
                fps = ((frames - lastFrames) * 1000 / (now - lastTime)).toInt().coerceAtLeast(0)
                lastFrames = frames; lastTime = now
            }
            return fps
        }
    }

    companion object {
        const val SCAN_INTERVAL_MS = 1500L
        const val REENUM_TIMEOUT_MS = 8000L
        const val REENUM_POLL_MS = 400L
        const val MAX_ACTIVATION_ATTEMPTS = 2
        const val PING_TIMEOUT_MS = 6000L
        const val STREAM_START_TIMEOUT_MS = 15000L
        const val PAIRING_WAIT_MAX_MS = 120_000L
        const val RESTART_AFTER_PAIRING_MS = 5000L
        const val CLOSE_TIMEOUT_MS = 3000L
        const val REPLUG_DEBOUNCE_MS = 4000L
    }
}

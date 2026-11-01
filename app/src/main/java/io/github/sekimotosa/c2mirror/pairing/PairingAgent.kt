package io.github.sekimotosa.c2mirror.pairing

import java.io.IOException

/**
 * iPhone に「このコンピュータを信頼しますか？」を表示させてペアリングし、lockdown セッションを確立する。
 * macOS / Linux の usbmuxd が接続時に行う処理（preflight）に相当する。
 * iPhone は信頼されていないホストには画面転送を開始しないと考えられるため、AV セッションと並行して実行する。
 */
class PairingAgent(
    private val mux: UsbMux,
    private val identity: () -> HostIdentity,
    private val log: (String) -> Unit,
    private val onState: (State, String?) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    enum class State { CONNECTING, WAITING_USER_TRUST, PAIRED, SESSION_OK, LOCKED, DENIED, FAILED }

    var productVersion: String? = null; private set
    var productType: String? = null; private set

    fun run() {
        try {
            onState(State.CONNECTING, null)
            mux.handshake()
            val id = identity()
            var pairedNow = false
            val deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS
            while (!isCancelled()) {
                val ld = LockdownClient(mux.connect(LOCKDOWN_PORT))
                try {
                    val type = ld.queryType()
                    log("lockdown: QueryType=$type")
                    if (tryStartSession(ld, id)) {
                        onState(State.SESSION_OK, productVersion)
                        return
                    }
                    if (pairedNow) throw IOException("StartSession failed right after pairing")
                    // 未ペア: iPhone の公開鍵で証明書を作り Pair を要求（iPhone に信頼ダイアログが出る）
                    val devKey = ld.getValue("DevicePublicKey") as? ByteArray ?: throw IOException("no DevicePublicKey")
                    val record = id.pairRecord(devKey)
                    while (!isCancelled()) {
                        val r = ld.request(mapOf(
                            "Request" to "Pair",
                            "ProtocolVersion" to "2",
                            "PairRecord" to record,
                            "PairingOptions" to mapOf("ExtendedPairingErrors" to true),
                        ), timeoutMs = 15000)
                        when (val err = r["Error"] as? String) {
                            null -> { log("lockdown: paired"); onState(State.PAIRED, null); pairedNow = true; break }
                            "PairingDialogResponsePending" -> onState(State.WAITING_USER_TRUST, null)
                            "PasswordProtected" -> onState(State.LOCKED, null)
                            "UserDeniedPairing" -> { onState(State.DENIED, null); return }
                            else -> throw LockdownException(err, "Pair: $err")
                        }
                        if (System.currentTimeMillis() > deadline) throw IOException("pairing timeout")
                        Thread.sleep(1000)
                    }
                } finally {
                    ld.close()
                }
            }
        } catch (e: Throwable) {
            if (!isCancelled()) {
                log("pairing failed: ${e.javaClass.simpleName}: ${e.message}")
                onState(State.FAILED, e.message)
            }
        }
    }

    /** StartSession（+TLS）を試す。未ペア（InvalidHostID 等）なら false */
    private fun tryStartSession(ld: LockdownClient, id: HostIdentity): Boolean {
        val r = ld.request(mapOf("Request" to "StartSession", "HostID" to id.hostId, "SystemBUID" to id.systemBuid))
        val err = r["Error"] as? String
        if (err != null) {
            log("lockdown: StartSession error=$err")
            if (err == "InvalidHostID" || err == "PairingProhibited" || err.contains("Pair")) return false
            throw LockdownException(err, "StartSession: $err")
        }
        if (r["EnableSessionSSL"] == true) {
            // TLS は lockdown と同じ TCP 接続上で開始する
            val tls = TlsStream(ld.currentStream(), TlsStream.context(id.hostKey, arrayOf(id.hostCert, id.rootCert)))
            tls.handshake()
            ld.upgrade(tls)
            log("lockdown: TLS session established")
        }
        productVersion = runCatching { ld.getValue("ProductVersion") as? String }.getOrNull()
        productType = runCatching { ld.getValue("ProductType") as? String }.getOrNull()
        log("lockdown: session OK (iOS $productVersion, $productType)")
        runCatching { ld.request(mapOf("Request" to "StopSession", "SessionID" to (r["SessionID"] ?: ""))) }
        return true
    }

    companion object {
        const val LOCKDOWN_PORT = 62078
        const val PAIR_TIMEOUT_MS = 120_000L
    }
}

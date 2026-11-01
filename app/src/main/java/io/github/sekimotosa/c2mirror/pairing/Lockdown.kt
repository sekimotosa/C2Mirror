package io.github.sekimotosa.c2mirror.pairing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

class LockdownException(val error: String, message: String = error) : IOException(message)

/**
 * lockdownd（iPhone のペアリング・セッション管理サービス, usbmux ポート 62078）クライアント。
 * メッセージ = [4byte 長さ(BE)] + XML plist。
 */
class LockdownClient(private var stream: ByteStream, private val label: String = "C2Mirror") {
    private val pending = ByteArrayOutputStream()

    fun request(req: Map<String, Any>, timeoutMs: Long = 10000): Map<String, Any> {
        val body = Plist.encode(LinkedHashMap<String, Any>().apply { put("Label", label); putAll(req) })
        stream.write(ByteBuffer.allocate(4).putInt(body.size).array() + body)
        val len = ByteBuffer.wrap(stream.readFully(4, timeoutMs, pending)).int
        if (len <= 0 || len > 4 * 1024 * 1024) throw IOException("lockdown: bad response length $len")
        return Plist.decodeDict(stream.readFully(len, timeoutMs, pending))
    }

    /** Error キーがあれば LockdownException を投げる */
    fun call(req: Map<String, Any>, timeoutMs: Long = 10000): Map<String, Any> {
        val r = request(req, timeoutMs)
        (r["Error"] as? String)?.let { throw LockdownException(it, "${req["Request"]}: $it") }
        return r
    }

    fun queryType(): String? = call(mapOf("Request" to "QueryType"))["Type"] as? String

    fun getValue(key: String?, domain: String? = null): Any? {
        val req = LinkedHashMap<String, Any>()
        req["Request"] = "GetValue"
        domain?.let { req["Domain"] = it }
        key?.let { req["Key"] = it }
        return call(req)["Value"]
    }

    fun currentStream(): ByteStream = stream

    /** SSL 有効化後はこのストリームを TLS に差し替える */
    fun upgrade(tls: ByteStream) {
        check(pending.size() == 0) { "unread data before TLS" }
        stream = tls
    }

    fun close() = runCatching { stream.close() }
}

/** ByteStream 上の TLS クライアント（SSLEngine） */
class TlsStream(private val raw: ByteStream, context: SSLContext) : ByteStream {
    private val engine: SSLEngine = context.createSSLEngine().apply {
        useClientMode = true
        // 古い iOS は TLS 1.0〜1.2 のみのため、利用可能なものは全て有効にする
        enabledProtocols = supportedProtocols.filter { it.startsWith("TLS") }.toTypedArray()
    }
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
    private val netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    fun handshake(timeoutMs: Long = 10000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        engine.beginHandshake()
        while (true) {
            if (System.currentTimeMillis() > deadline) throw IOException("TLS handshake timeout")
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> if (!unwrapOnce(deadline - System.currentTimeMillis())) continue
                SSLEngineResult.HandshakeStatus.NEED_TASK -> { while (true) { engine.delegatedTask?.run() ?: break } }
                else -> return
            }
        }
    }

    private fun wrap(src: ByteBuffer) {
        do {
            netOut.clear()
            val r = engine.wrap(src, netOut)
            if (r.status != SSLEngineResult.Status.OK && r.status != SSLEngineResult.Status.CLOSED) {
                throw IOException("TLS wrap: ${r.status}")
            }
            netOut.flip()
            if (netOut.hasRemaining()) {
                val b = ByteArray(netOut.remaining()); netOut.get(b); raw.write(b)
            }
        } while (src.hasRemaining())
    }

    /** 1 回 unwrap を試みる。データ不足なら受信して false */
    private fun unwrapOnce(timeoutMs: Long): Boolean {
        appIn.compact()
        try {
            val r = engine.unwrap(netIn, appIn)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    val more = raw.readSome(maxOf(1, timeoutMs)) ?: throw IOException("TLS: no data from device")
                    netIn.compact()
                    if (netIn.remaining() < more.size) {
                        val n = ByteBuffer.allocate(netIn.position() + more.size + engine.session.packetBufferSize)
                        netIn.flip(); n.put(netIn); netIn = n
                    }
                    netIn.put(more); netIn.flip()
                    return false
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val n = ByteBuffer.allocate(appIn.capacity() * 2); appIn.flip(); n.put(appIn); appIn = n
                    return false
                }
                SSLEngineResult.Status.CLOSED -> throw IOException("TLS closed by device")
                else -> return true
            }
        } finally {
            appIn.flip()
        }
    }

    override fun write(data: ByteArray) = wrap(ByteBuffer.wrap(data))

    override fun readSome(timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!appIn.hasRemaining()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            unwrapOnce(left)
        }
        val b = ByteArray(appIn.remaining()); appIn.get(b); return b
    }

    override fun close() {
        runCatching { engine.closeOutbound(); wrap(ByteBuffer.allocate(0)) }
        raw.close()
    }

    companion object {
        /** ホスト証明書でクライアント認証し、デバイス証明書は検証しない（ペア済み相手との閉じた USB 通信） */
        fun context(hostKey: PrivateKey, hostChain: Array<X509Certificate>): SSLContext {
            val km = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("host")
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "host"
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "host"
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getCertificateChain(alias: String?) = hostChain
                override fun getPrivateKey(alias: String?) = hostKey
            }
            val tm = object : X509ExtendedTrustManager() {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }
            return SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(tm), null) }
        }
    }
}

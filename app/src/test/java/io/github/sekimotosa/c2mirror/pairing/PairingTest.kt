package io.github.sekimotosa.c2mirror.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.Principal
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult

class PlistTest {
    @Test fun roundTrip() {
        val m = linkedMapOf<String, Any>(
            "Request" to "Pair", "ProtocolVersion" to "2", "N" to 42L, "B" to true, "F" to false,
            "D" to byteArrayOf(1, 2, 3, 0xFF.toByte()), "Nested" to mapOf("ExtendedPairingErrors" to true),
            "Arr" to listOf("a", 1L), "Esc" to "<a & b>",
        )
        val back = Plist.decodeDict(Plist.encode(m))
        assertEquals("Pair", back["Request"])
        assertEquals(42L, back["N"])
        assertEquals(true, back["B"]); assertEquals(false, back["F"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 0xFF.toByte()), back["D"] as ByteArray)
        assertEquals(true, (back["Nested"] as Map<*, *>)["ExtendedPairingErrors"])
        assertEquals(listOf("a", 1L), back["Arr"])
        assertEquals("<a & b>", back["Esc"])
    }

    @Test fun decodesAppleStyleResponse() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Request</key>
	<string>QueryType</string>
	<key>Type</key>
	<string>com.apple.mobile.lockdown</string>
</dict>
</plist>
"""
        assertEquals("com.apple.mobile.lockdown", Plist.decodeDict(xml.toByteArray())["Type"])
    }
}

class CertificatesTest {
    @Test fun hostIdentityCertificatesVerify() {
        val id = HostIdentity.generate()
        id.rootCert.verify(id.rootCert.publicKey)
        id.hostCert.verify(id.rootCert.publicKey)
        assertTrue(id.rootCert.basicConstraints >= 0) // CA
        assertEquals(-1, id.hostCert.basicConstraints) // 非 CA
        assertTrue(id.hostCert.keyUsage[0] && id.hostCert.keyUsage[2])
        assertEquals(36, id.hostId.length)

        val back = HostIdentity.deserialize(id.serialize())!!
        assertEquals(id.hostId, back.hostId)
        assertArrayEquals(id.hostCert.encoded, back.hostCert.encoded)
        assertArrayEquals(id.hostKey.encoded, back.hostKey.encoded)
    }

    @Test fun deviceCertificateFromPkcs1Pem() {
        val id = HostIdentity.generate()
        val device = Certificates.generateRsaKeyPair()
        // iPhone が返す形式: PKCS#1 "RSA PUBLIC KEY"（SPKI の BIT STRING の中身）
        val spki = device.public.encoded
        val pkcs1 = spki.copyOfRange(spki.size - pkcs1Length(spki), spki.size)
        val pem = Certificates.pem("RSA PUBLIC KEY", pkcs1)
        val certPem = String(id.deviceCertificatePem(pem))
        assertTrue(certPem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        val der = Base64.getMimeDecoder().decode(certPem.lines().filter { !it.startsWith("-----") }.joinToString(""))
        val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        cert.verify(id.rootCert.publicKey)
        assertArrayEquals(device.public.encoded, cert.publicKey.encoded)

        val record = id.pairRecord(pem)
        assertEquals(setOf("DeviceCertificate", "HostCertificate", "HostID", "RootCertificate", "SystemBUID"), record.keys)
    }

    /** SPKI 末尾の BIT STRING 内 PKCS#1 の長さ（テスト用の簡易 DER 解析） */
    private fun pkcs1Length(spki: ByteArray): Int {
        // 末尾から PKCS#1 SEQUENCE(0x30) を探す: BIT STRING の 0x00 の直後
        for (i in spki.indices) if (spki[i] == 0x03.toByte()) {
            val l = spki[i + 1].toInt() and 0xFF
            val hl = if (l < 0x80) 2 else 2 + (l and 0x7F)
            if (spki.getOrNull(i + hl) == 0.toByte() && spki.getOrNull(i + hl + 1) == 0x30.toByte()) return spki.size - (i + hl + 1)
        }
        error("no bit string")
    }
}

/** usbmux / TCP / lockdown を擬似デバイスで検証 */
class UsbMuxTest {
    private class FakeDevice {
        val toHost = LinkedBlockingQueue<ByteArray>()
        var version = 0
        var hostSeqSeen = -1
        var deviceSeq = 1000
        var devTx = 0
        val received = ByteArrayOutputStream()
        lateinit var mux: UsbMux
        var refuse = false
        val lockdownIn = ByteArrayOutputStream()

        fun fromHost(p: ByteArray) {
            val b = ByteBuffer.wrap(p)
            val proto = b.getInt(0)
            val len = b.getInt(4)
            assertEquals(p.size, len)
            var h = 8
            if (version >= 2 && proto != 0) {
                assertEquals(UsbMux.MAGIC, b.getInt(8))
                hostSeqSeen = b.getShort(12).toInt() and 0xFFFF
                h = 16
            }
            when (proto) {
                0 -> { version = 2; send(0, ByteBuffer.allocate(12).putInt(2).putInt(0).putInt(0).array(), v1 = true) }
                2 -> assertArrayEquals(byteArrayOf(7), p.copyOfRange(h, p.size))
                6 -> {
                    val sport = b.getShort(h).toInt() and 0xFFFF
                    val dport = b.getShort(h + 2).toInt() and 0xFFFF
                    val seq = b.getInt(h + 4)
                    val flags = p[h + 13].toInt()
                    val payload = p.copyOfRange(h + 20, p.size)
                    if (flags and UsbMux.TH_SYN != 0) {
                        assertEquals(62078, dport)
                        if (refuse) tcp(dport, sport, 0, seq + 1, UsbMux.TH_RST)
                        else tcp(dport, sport, deviceSeq, seq + 1, UsbMux.TH_SYN or UsbMux.TH_ACK)
                    } else if (payload.isNotEmpty()) {
                        lockdownIn.write(payload)
                        val all = lockdownIn.toByteArray()
                        if (all.size >= 4 && all.size >= 4 + ByteBuffer.wrap(all).int) {
                            lockdownIn.reset()
                            val req = Plist.decodeDict(all.copyOfRange(4, all.size))
                            val resp = Plist.encode(mapOf("Request" to req["Request"]!!, "Type" to "com.apple.mobile.lockdown"))
                            tcp(dport, sport, deviceSeq + 1, seq + payload.size, UsbMux.TH_ACK,
                                ByteBuffer.allocate(4).putInt(resp.size).array() + resp)
                        }
                    }
                }
            }
        }

        fun tcp(sport: Int, dport: Int, seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0)) {
            val b = ByteBuffer.allocate(20 + payload.size)
            b.putShort(sport.toShort()); b.putShort(dport.toShort()); b.putInt(seq); b.putInt(ack)
            b.put(0x50); b.put(flags.toByte()); b.putShort(512); b.putShort(0); b.putShort(0); b.put(payload)
            send(6, b.array())
        }

        fun send(proto: Int, payload: ByteArray, v1: Boolean = false) {
            val h = if (v1) 8 else 16
            val b = ByteBuffer.allocate(h + payload.size).putInt(proto).putInt(h + payload.size)
            if (!v1) { b.putInt(UsbMux.MAGIC); b.putShort((devTx++).toShort()); b.putShort(0) }
            b.put(payload)
            val a = b.array()
            // USB 受信が分割されるケースも検証するため 2 回に分けて渡す
            val cut = a.size / 2
            mux.onUsbData(a, 0, cut); mux.onUsbData(a, cut, a.size - cut)
        }
    }

    @Test fun versionConnectAndLockdownQuery() {
        val dev = FakeDevice()
        val mux = UsbMux({ dev.fromHost(it) })
        dev.mux = mux
        assertEquals(2, mux.handshake(1000))
        val conn = mux.connect(62078, 1000)
        val ld = LockdownClient(conn)
        assertEquals("com.apple.mobile.lockdown", ld.queryType())
        assertTrue(dev.hostSeqSeen >= 2)
    }

    @Test(expected = java.io.IOException::class)
    fun refusedConnection() {
        val dev = FakeDevice().apply { refuse = true }
        val mux = UsbMux({ dev.fromHost(it) })
        dev.mux = mux
        mux.handshake(1000)
        mux.connect(62078, 1000)
    }
}

/** TlsStream（SSLEngine ループ）をメモリ上のサーバーと接続して検証 */
class TlsStreamTest {
    private class Pipe : ByteStream {
        val q = LinkedBlockingQueue<ByteArray>()
        lateinit var peer: Pipe
        override fun write(data: ByteArray) { peer.q.offer(data.copyOf()) }
        override fun readSome(timeoutMs: Long): ByteArray? = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        override fun close() {}
    }

    @Test fun handshakeAndEcho() {
        val id = HostIdentity.generate()
        val devKeys = Certificates.generateRsaKeyPair()
        val devCert = Certificates.createCertificate(devKeys.public.encoded, "Device", "C2Mirror Root CA", id.rootKey, false, 1)
        val ks = java.security.KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("d", devKeys.private, CharArray(0), arrayOf(devCert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll()), null) }

        val a = Pipe(); val b = Pipe(); a.peer = b; b.peer = a
        val server = Thread {
            val e = serverCtx.createSSLEngine().apply { useClientMode = false; needClientAuth = true }
            // サーバー側は簡易に TlsStream と同じ処理を逆向きで行う
            val s = ServerTls(b, e)
            s.handshake()
            val got = s.read()
            s.write(got.reversedArray())
        }
        server.start()
        val client = TlsStream(a, TlsStream.context(id.hostKey, arrayOf(id.hostCert, id.rootCert)))
        client.handshake(10000)
        client.write("hello".toByteArray())
        var resp: ByteArray? = null
        val deadline = System.currentTimeMillis() + 5000
        while (resp == null && System.currentTimeMillis() < deadline) resp = client.readSome(500)
        assertEquals("olleh", String(resp!!))
        server.join(5000)
    }

    private fun trustAll() = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private class ServerTls(val raw: Pipe, val e: javax.net.ssl.SSLEngine) {
        var netIn: ByteBuffer = ByteBuffer.allocate(64 * 1024).apply { flip() }
        val app: ByteBuffer = ByteBuffer.allocate(64 * 1024)
        fun handshake() {
            e.beginHandshake()
            while (true) when (e.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> write(ByteArray(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrap()
                SSLEngineResult.HandshakeStatus.NEED_TASK -> while (true) { e.delegatedTask?.run() ?: break }
                else -> return
            }
        }
        fun unwrap() {
            val r = e.unwrap(netIn, app)
            if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                val more = raw.readSome(5000)!!
                netIn.compact(); netIn.put(more); netIn.flip()
            }
        }
        fun write(d: ByteArray) {
            val src = ByteBuffer.wrap(d)
            do {
                val out = ByteBuffer.allocate(64 * 1024)
                e.wrap(src, out); out.flip()
                if (out.hasRemaining()) raw.write(ByteArray(out.remaining()).also { out.get(it) })
            } while (src.hasRemaining())
        }
        fun read(): ByteArray {
            while (app.position() == 0) unwrap()
            app.flip(); return ByteArray(app.remaining()).also { app.get(it); app.clear() }
        }
    }
}

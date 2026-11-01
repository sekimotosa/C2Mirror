package io.github.sekimotosa.c2mirror.pairing

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 最小限の DER エンコーダ（X.509 証明書の生成用）。 */
object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, concat(*items))
    fun set(vararg items: ByteArray) = tlv(0x31, concat(*items))
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Long) = int(BigInteger.valueOf(v))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun explicit(n: Int, content: ByteArray) = tlv(0xA0 + n, content)
    fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    fun oid(s: String): ByteArray {
        val parts = s.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            var v = p
            val stack = ArrayList<Int>()
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun concat(vararg a: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        a.forEach { out.write(it) }
        return out.toByteArray()
    }
}

/**
 * lockdown ペアリング用の証明書群。
 * Root(自己署名 CA) が Host 証明書と Device 証明書（iPhone の公開鍵）に署名する。
 */
object Certificates {
    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"

    fun generateRsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /** "-----BEGIN RSA PUBLIC KEY-----"(PKCS#1) もしくは "BEGIN PUBLIC KEY"(SPKI) の PEM から SPKI DER を得る */
    fun spkiFromPem(pem: ByteArray): ByteArray {
        val text = String(pem, Charsets.US_ASCII)
        val body = text.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val der = Base64.getDecoder().decode(body)
        return if (text.contains("BEGIN RSA PUBLIC KEY")) {
            Der.seq(Der.seq(Der.oid(OID_RSA), Der.nul()), Der.bits(der))
        } else der
    }

    fun publicKeyFromSpki(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(spki))

    private fun name(cn: String?) = if (cn == null) Der.seq() else
        Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(cn))))

    /** SPKI の BIT STRING 部分の SHA-1（SubjectKeyIdentifier 用） */
    private fun keyId(spki: ByteArray): ByteArray {
        // SPKI = SEQ { AlgId, BIT STRING }。末尾の BIT STRING の中身（先頭の未使用ビット数を除く）をハッシュ
        val algLen = spki.let { s -> derElementLength(s, headerLength(s, 0)) }
        val bitStart = headerLength(spki, 0) + algLen
        val bitHeader = headerLength(spki, bitStart)
        val bitLen = derContentLength(spki, bitStart)
        val key = spki.copyOfRange(bitStart + bitHeader + 1, bitStart + bitHeader + bitLen)
        return MessageDigest.getInstance("SHA-1").digest(key)
    }

    private fun headerLength(d: ByteArray, off: Int): Int {
        val l = d[off + 1].toInt() and 0xFF
        return if (l < 0x80) 2 else 2 + (l and 0x7F)
    }

    private fun derContentLength(d: ByteArray, off: Int): Int {
        val l = d[off + 1].toInt() and 0xFF
        if (l < 0x80) return l
        var v = 0
        for (i in 0 until (l and 0x7F)) v = (v shl 8) or (d[off + 2 + i].toInt() and 0xFF)
        return v
    }

    private fun derElementLength(d: ByteArray, off: Int) = headerLength(d, off) + derContentLength(d, off)

    fun createCertificate(
        subjectSpki: ByteArray,
        subjectCn: String?,
        issuerCn: String?,
        issuerKey: PrivateKey,
        isCa: Boolean,
        serial: Long,
        years: Int = 10,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + years * 365L * 24 * 3600 * 1000)
        val sigAlg = Der.seq(Der.oid(OID_SHA256_RSA), Der.nul())
        val basicConstraints = Der.seq(Der.oid("2.5.29.19"), Der.bool(true),
            Der.octets(if (isCa) Der.seq(Der.bool(true)) else Der.seq()))
        // keyUsage: CA = keyCertSign|cRLSign (bits 5,6) / それ以外 = digitalSignature|keyEncipherment (bits 0,2)
        val ku = if (isCa) byteArrayOf(0x01, 0x06) else byteArrayOf(0x05, 0xA0.toByte())
        val keyUsage = Der.seq(Der.oid("2.5.29.15"), Der.bool(true), Der.octets(Der.tlv(0x03, ku)))
        val ski = Der.seq(Der.oid("2.5.29.14"), Der.octets(Der.octets(keyId(subjectSpki))))
        val tbs = Der.seq(
            Der.explicit(0, Der.int(2)),
            Der.int(serial),
            sigAlg,
            name(issuerCn),
            Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            name(subjectCn),
            subjectSpki,
            Der.explicit(3, Der.seq(basicConstraints, keyUsage, ski)),
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(issuerKey); update(tbs) }.sign()
        val der = Der.seq(tbs, sigAlg, Der.bits(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    fun pem(cert: X509Certificate): ByteArray = pem("CERTIFICATE", cert.encoded)

    fun pem(type: String, der: ByteArray): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n".toByteArray(Charsets.US_ASCII)
    }
}

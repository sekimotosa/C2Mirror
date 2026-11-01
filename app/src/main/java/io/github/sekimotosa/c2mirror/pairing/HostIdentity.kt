package io.github.sekimotosa.c2mirror.pairing

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * このアプリ（ホスト）のペアリング用の身元情報。端末内にのみ保存し、外部へは出さない。
 * Root CA と Host 証明書は全 iPhone 共通、Device 証明書はペアリング時に iPhone の公開鍵から作る。
 */
class HostIdentity(
    val hostId: String,
    val systemBuid: String,
    val rootKey: PrivateKey,
    val rootCert: X509Certificate,
    val hostKey: PrivateKey,
    val hostCert: X509Certificate,
) {
    fun deviceCertificatePem(devicePublicKeyPem: ByteArray): ByteArray {
        val spki = Certificates.spkiFromPem(devicePublicKeyPem)
        val cert = Certificates.createCertificate(spki, "Device", ROOT_CN, rootKey, isCa = false, serial = 0)
        return Certificates.pem(cert)
    }

    /** lockdown の Pair リクエストに入れる PairRecord（秘密鍵は含めない） */
    fun pairRecord(devicePublicKeyPem: ByteArray): Map<String, Any> = linkedMapOf(
        "DeviceCertificate" to deviceCertificatePem(devicePublicKeyPem),
        "HostCertificate" to Certificates.pem(hostCert),
        "HostID" to hostId,
        "RootCertificate" to Certificates.pem(rootCert),
        "SystemBUID" to systemBuid,
    )

    fun serialize(): Map<String, String> {
        val e = Base64.getEncoder()
        return mapOf(
            "hostId" to hostId, "systemBuid" to systemBuid,
            "rootKey" to e.encodeToString(rootKey.encoded), "rootCert" to e.encodeToString(rootCert.encoded),
            "hostKey" to e.encodeToString(hostKey.encoded), "hostCert" to e.encodeToString(hostCert.encoded),
        )
    }

    companion object {
        private const val ROOT_CN = "C2Mirror Root CA"

        fun generate(): HostIdentity {
            val root = Certificates.generateRsaKeyPair()
            val host = Certificates.generateRsaKeyPair()
            val rootCert = Certificates.createCertificate(root.public.encoded, ROOT_CN, ROOT_CN, root.private, isCa = true, serial = 0)
            val hostCert = Certificates.createCertificate(host.public.encoded, "C2Mirror Host", ROOT_CN, root.private, isCa = false, serial = 0)
            return HostIdentity(
                UUID.randomUUID().toString().uppercase(), UUID.randomUUID().toString().uppercase(),
                root.private, rootCert, host.private, hostCert,
            )
        }

        fun deserialize(m: Map<String, String>): HostIdentity? = runCatching {
            val d = Base64.getDecoder()
            val kf = KeyFactory.getInstance("RSA")
            val cf = CertificateFactory.getInstance("X.509")
            HostIdentity(
                m.getValue("hostId"), m.getValue("systemBuid"),
                kf.generatePrivate(PKCS8EncodedKeySpec(d.decode(m.getValue("rootKey")))),
                cf.generateCertificate(d.decode(m.getValue("rootCert")).inputStream()) as X509Certificate,
                kf.generatePrivate(PKCS8EncodedKeySpec(d.decode(m.getValue("hostKey")))),
                cf.generateCertificate(d.decode(m.getValue("hostCert")).inputStream()) as X509Certificate,
            )
        }.getOrNull()
    }
}

package com.proofstamp.app.data.c2pa

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.ContentSigner
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * C2PA signing identity: an EC P-256 key inside Android Keystore (private key never leaves
 * hardware/TEE) plus a self-signed X.509 v3 end-entity certificate shaped to the C2PA
 * §14.5 certificate profile (KU=digitalSignature, EKU=documentSigning, AKI present,
 * BasicConstraints CA=false). The certificate is stored as PEM in app-private storage;
 * it carries no secrets — only the public key bound to the keystore key.
 */
class C2paSigner(private val context: Context) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private val certFile: File get() = File(context.filesDir, "c2pa_signing_cert.pem")

    /** Ensures the keystore key + certificate exist; returns true when the pair is usable. */
    fun ensureIdentity(): Boolean = try {
        ensureKey()
        certificatePem()
        true
    } catch (e: Throwable) {
        android.util.Log.e("C2paSigner", "identity setup failed", e)
        false
    }

    /**
     * A C2PA signer. When a provisioned cert chain + key (e.g. CAWG/C2PA trust-list
     * issued, imported via Settings) is present it is used directly; otherwise private-key
     * operations run inside Android Keystore with our conformant self-signed cert.
     */
    fun newSigner(): Signer =
        if (hasTrustedIdentity()) {
            Signer.fromKeys(
                certsPEM = trustedChainPem(),
                privateKeyPEM = trustedKeyPem(),
                algorithm = SigningAlgorithm.ES256,
                tsaURL = null,
            )
        } else {
            Signer.withCallback(
                algorithm = SigningAlgorithm.ES256,
                certificateChainPEM = certificatePem(),
                tsaURL = null,
            ) { data -> sign(data) }
        }

    // --- Trusted identity (provisioned chain + key imported via Settings) ---

    private val identityDir: File get() = File(context.filesDir, "c2pa_identity")
    private val chainFile get() = File(identityDir, "chain.pem")
    private val keyFile get() = File(identityDir, "key.pem")

    /** True when a provisioned (e.g. CAWG-issued) cert chain + private key is imported. */
    fun hasTrustedIdentity(): Boolean = chainFile.isFile && keyFile.isFile

    /** Human-readable subject of the active identity, for display in settings. */
    fun identitySubject(): String =
        if (hasTrustedIdentity()) {
            runCatching {
                val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(chainFile.inputStream()) as X509Certificate
                cert.subjectX500Principal.name
            }.getOrElse { "Trusted identity (imported)" }
        } else {
            "${certificateSubject()} (self-signed)"
        }

    /** Imports a PEM cert chain + PEM EC private key (PKCS8). Returns error text or null on success. */
    fun importTrustedIdentity(chainPem: ByteArray, keyPem: ByteArray): String? = try {
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(chainPem.inputStream())
        java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(decodePem(keyPem)))
        identityDir.mkdirs()
        chainFile.writeBytes(chainPem)
        keyFile.writeBytes(keyPem)
        null
    } catch (e: Exception) {
        "Invalid PEM files: ${e.message}"
    }

    fun clearTrustedIdentity() {
        chainFile.delete()
        keyFile.delete()
    }

    private fun trustedChainPem(): String = chainFile.readText()
    private fun trustedKeyPem(): String = keyFile.readText()

    private fun decodePem(pem: ByteArray): ByteArray =
        java.util.Base64.getMimeDecoder().decode(
            String(pem).replace(Regex("-----[A-Z ]+-----"), "").trim(),
        )

    /** PEM-encoded signing certificate, generating a conformant self-signed cert on first use. */
    fun certificatePem(): String {
        val cached = certFile.takeIf { it.isFile }?.readText()
        if (!cached.isNullOrBlank()) return cached
        val pem = generateSelfSignedCert().toPem()
        certFile.writeText(pem)
        return pem
    }

    /** Human-readable subject of the signing certificate, for display in settings. */
    fun certificateSubject(): String = try {
        certificatePem()
        (keyStore.getCertificate(KEY_ALIAS) as? X509Certificate)?.subjectX500Principal?.name ?: SUBJECT_DN
    } catch (_: Exception) {
        SUBJECT_DN
    }

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(privateKey())
            update(data)
            derToP1363(sign())
        }

    /**
     * Android's Signature returns ECDSA as DER; COSE/c2pa-rs expects raw R||S
     * (IEEE P1363) — two 32-byte unsigned big-endian integers for P-256.
     */
    private fun derToP1363(der: ByteArray): ByteArray {
        var p = 0
        if (der[p++].toInt() != 0x30) throw IllegalArgumentException("bad DER sig")
        var len = der[p++].toInt() and 0xFF
        if (len and 0x80 != 0) p += len and 0x7F // long-form length bytes
        val out = ByteArray(64)
        for (slot in 0..1) {
            if (der[p++].toInt() != 0x02) throw IllegalArgumentException("bad DER sig")
            val ilen = der[p++].toInt() and 0xFF
            val srcOff = if (ilen == 33 && der[p].toInt() == 0) p + 1 else p
            val copy = ilen - (srcOff - p)
            System.arraycopy(der, srcOff, out, slot * 32 + (32 - copy), copy)
            p += ilen
        }
        return out
    }

    private fun privateKey(): PrivateKey =
        (keyStore.getKey(KEY_ALIAS, null) as? PrivateKey)
            ?: throw IllegalStateException("C2PA signing key missing")

    private fun ensureKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal(SUBJECT_DN))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date())
                .setCertificateNotAfter(Date(System.currentTimeMillis() + CERT_VALIDITY_MS))
                .build(),
        )
        kpg.generateKeyPair()
    }

    /**
     * Builds a C2PA-conformant self-signed end-entity certificate over the keystore key.
     * Signed via the AndroidKeyStore Signature provider so the private key stays non-exportable.
     */
    private fun generateSelfSignedCert(): X509Certificate {
        ensureKey()
        val pubKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        val subject = X500Name(SUBJECT_DN)
        val pubKeyInfo = SubjectPublicKeyInfo.getInstance(pubKey.encoded)

        val builder = X509v3CertificateBuilder(
            subject, // self-signed: issuer == subject (allowed — cert is an end-entity, not a CA)
            BigInteger.valueOf(System.currentTimeMillis()),
            Date(System.currentTimeMillis() - 60_000L),
            Date(System.currentTimeMillis() + CERT_VALIDITY_MS),
            subject,
            pubKeyInfo,
        )

        // C2PA certificate profile (spec §14.5): KU digitalSignature, a whitelisted EKU,
        // AuthorityKeyIdentifier present, CA=false, and no unhandled critical extensions.
        val extUtils = BcX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            // id-kp-documentSigning (1.3.6.1.5.5.7.3.36), whitelisted by the C2PA cert profile.
            ExtendedKeyUsage(KeyPurposeId.getInstance(ASN1ObjectIdentifier("1.3.6.1.5.5.7.3.36"))),
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(pubKeyInfo))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(pubKeyInfo))

        return JcaX509CertificateConverter().getCertificate(builder.build(KeystoreContentSigner(privateKey(), SIGN_ALGORITHM)))
    }

    private fun X509Certificate.toPem(): String {
        val b64 = Base64.encodeToString(encoded, Base64.DEFAULT)
        return "-----BEGIN CERTIFICATE-----\n$b64-----END CERTIFICATE-----\n"
    }

    /** Signs the certificate's TBS bytes through Android Keystore — the key never leaves it. */
    private class KeystoreContentSigner(
        private val key: PrivateKey,
        private val jcaAlgorithm: String,
    ) : ContentSigner {
        private val out = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            when (jcaAlgorithm) {
                "SHA256withECDSA" -> AlgorithmIdentifier(ECDSA_SHA256_OID)
                else -> throw IllegalArgumentException(jcaAlgorithm)
            }

        override fun getOutputStream(): ByteArrayOutputStream {
            out.reset()
            return out
        }

        override fun getSignature(): ByteArray =
            // The "AndroidKeyStore" provider does not expose Signature services
            // directly; provider-less getInstance resolves to the keystore-backed
            // AndroidKeyStoreBCWorkaround provider for keystore keys.
            Signature.getInstance(jcaAlgorithm).run {
                initSign(key)
                update(out.toByteArray())
                sign()
            }

        companion object {
            private val ECDSA_SHA256_OID = ASN1ObjectIdentifier("1.2.840.10045.4.3.2")
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "proofstamp_c2pa_signing_key_v1"
        private const val SIGN_ALGORITHM = "SHA256withECDSA"
        private const val SUBJECT_DN = "CN=ProofStamp Verified Capture, O=ProofStamp, OU=Capture"
        private const val CERT_VALIDITY_MS = 25L * 365 * 24 * 60 * 60 * 1000
    }
}

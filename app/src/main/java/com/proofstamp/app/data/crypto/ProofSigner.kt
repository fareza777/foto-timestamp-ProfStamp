package com.proofstamp.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Local digital signature over the proof hash using a hardware-backed (when available)
 * EC P-256 key that never leaves the device. This proves a record was sealed by *this*
 * installation and was not fabricated later by editing the database.
 */
class ProofSigner(private val alias: String = DEFAULT_ALIAS) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun ensureKey(): PrivateKey {
        val existing = keyStore.getKey(alias, null) as? PrivateKey
        if (existing != null) return existing
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return kpg.generateKeyPair().private
    }

    fun publicKeyBase64(): String {
        ensureKey()
        val cert = keyStore.getCertificate(alias)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    fun sign(proofHashHex: String): String {
        val key = ensureKey()
        val sig = Signature.getInstance(ALGORITHM).apply {
            initSign(key)
            update(proofHashHex.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verify(proofHashHex: String, signatureBase64: String, publicKeyBase64: String): Boolean = try {
        val pub: PublicKey = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.NO_WRAP)))
        Signature.getInstance(ALGORITHM).run {
            initVerify(pub)
            update(proofHashHex.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "proofstamp_signing_key_v1"
        private const val ALGORITHM = "SHA256withECDSA"
    }
}

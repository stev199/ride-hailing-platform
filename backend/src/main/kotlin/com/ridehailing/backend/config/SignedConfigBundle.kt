package com.ridehailing.backend.config

import com.ridehailing.backend.events.EventChain
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Signed configuration bundles (B-01/A-10 core).
 *
 * Devices receive bundles (max hops, OTP channels, fees, feature flags, ...) signed
 * with the backend's Ed25519 key. Anti-rollback: a device rejects any bundle whose
 * version is <= the highest version it has accepted. Signature covers the canonical
 * string "version|canonical(config)|issuedAt".
 *
 * The dev keypair is for tests/dev bootstrapping only; production keys are injected
 * via secrets management, never in code.
 */
class SignedConfigBundle private constructor(
    val publicKeyBytes: ByteArray,
    private val privateKeyBytes: ByteArray?,
) {
    data class SignedConfig(
        val version: Long,
        val configJson: String,
        val issuedAtSec: Long,
        val signature: ByteArray,
    )

    sealed class VerifyResult {
        data object Valid : VerifyResult()
        data object BadSignature : VerifyResult()
        data object RolledBack : VerifyResult()
    }

    fun sign(configCanonicalJson: String, version: Long, issuedAtSec: Long): SignedConfig? {
        val priv = privateKeyBytes ?: return null
        val content = "$version|$configCanonicalJson|$issuedAtSec"
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(restorePrivateKey(priv))
        sig.update(content.toByteArray(Charsets.UTF_8))
        return SignedConfig(version, configCanonicalJson, issuedAtSec, sig.sign())
    }

    fun verify(bundle: SignedConfig, minVersion: Long): VerifyResult {
        if (bundle.version <= minVersion) return VerifyResult.RolledBack
        val content = "${bundle.version}|${bundle.configJson}|${bundle.issuedAtSec}"
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(restorePublicKey(publicKeyBytes))
            sig.update(content.toByteArray(Charsets.UTF_8))
            if (sig.verify(bundle.signature)) VerifyResult.Valid else VerifyResult.BadSignature
        } catch (_: Exception) {
            VerifyResult.BadSignature
        }
    }

    companion object {
        fun fromKeys(publicKeyBytes: ByteArray, privateKeyBytes: ByteArray?): SignedConfigBundle =
            SignedConfigBundle(publicKeyBytes, privateKeyBytes)

        /** Dev/test only - throws away keys at process exit; production injects real keys. */
        fun inMemoryDevKeys(): SignedConfigBundle {
            val kp = generateKeyPair()
            return fromKeys(kp.public.encoded, kp.private.encoded)
        }

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        fun restorePublicKey(bytes: ByteArray): PublicKey =
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(bytes))

        fun restorePrivateKey(bytes: ByteArray): PrivateKey =
            java.security.KeyFactory.getInstance("Ed25519").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(bytes))
    }
}

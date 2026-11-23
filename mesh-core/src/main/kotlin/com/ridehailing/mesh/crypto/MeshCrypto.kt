package com.ridehailing.mesh.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPublicKeySpec
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stage 1 crypto on pure JDK 17 primitives (no external dependency):
 *  - identity: Ed25519 signing (origin signatures; signature covers immutable header + payload)
 *  - E2E confidentiality: X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 sealed-box style
 *  - rotating originId: truncated HMAC(deviceSecret, epoch) (default rotation 15 min, admin-config)
 *  - per-hop MAC: HMAC-SHA256(linkKey, hopSection)
 *
 * RFC known-answer tests pin Ed25519 (RFC 8032 test 1) and ChaCha20-Poly1305 (RFC 8439 2.8.2).
 * Decision T-2 (Tink vs libsodium) resolved to JDK built-ins for mesh-core; revisit only if
 * Android Keystore integration demands it (see DECISIONS.md).
 */
object MeshCrypto {
    private val ed25519Oid = byteArrayOf(0x2b.toByte(), 0x65, 0x70)
    private val x25519Oid = byteArrayOf(0x2b.toByte(), 0x65, 0x6e.toByte())
    private val random = SecureRandom()

    // ---------- Ed25519 identity ----------

    fun newIdentity(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun sign(privateKey: PrivateKey, content: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(privateKey)
        s.update(content)
        return s.sign()
    }

    fun verify(publicKey: PublicKey, content: ByteArray, signature: ByteArray): Boolean = try {
        val s = Signature.getInstance("Ed25519")
        s.initVerify(publicKey)
        s.update(content)
        s.verify(signature)
    } catch (_: Exception) {
        false
    }

    /** Raw 32-byte seed -> PrivateKey (PKCS8-wrapped). */
    fun ed25519PrivateKeyFromSeed(seed: ByteArray): PrivateKey {
        require(seed.size == 32)
        val pkcs8 = ByteArray(16 + 32)
        pkcs8[0] = 0x30; pkcs8[1] = 0x2e.toByte(); pkcs8[2] = 0x02; pkcs8[3] = 0x01; pkcs8[4] = 0x00
        pkcs8[5] = 0x30; pkcs8[6] = 0x05; pkcs8[7] = 0x06; pkcs8[8] = 0x03
        ed25519Oid.copyInto(pkcs8, 9)
        pkcs8[12] = 0x04; pkcs8[13] = 0x22; pkcs8[14] = 0x04; pkcs8[15] = 0x20
        seed.copyInto(pkcs8, 16)
        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    /** Raw 32-byte public key -> PublicKey (X509-wrapped). */
    fun ed25519PublicKeyFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == 32)
        val x509 = ByteArray(12 + 32)
        x509[0] = 0x30; x509[1] = 0x2a.toByte(); x509[2] = 0x30; x509[3] = 0x05; x509[4] = 0x06; x509[5] = 0x03
        ed25519Oid.copyInto(x509, 6)
        x509[9] = 0x03; x509[10] = 0x21; x509[11] = 0x00
        raw.copyInto(x509, 12)
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509))
    }

    // ---------- X25519 + ChaCha20-Poly1305 sealed box ----------

    fun newX25519KeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun x25519PublicFromPrivate(privateKey: PrivateKey): PublicKey {
        val base = KeyFactory.getInstance("X25519")
            .generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, java.math.BigInteger.valueOf(9)))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privateKey); ka.doPhase(base, true)
        val u = ka.generateSecret()
        return KeyFactory.getInstance("X25519")
            .generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, java.math.BigInteger(1, u.reversedArray())))
    }

    fun x25519(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privateKey); ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }

    fun x25519PublicFromRaw(raw: ByteArray): PublicKey =
        KeyFactory.getInstance("X25519").generatePublic(
            XECPublicKeySpec(NamedParameterSpec.X25519, java.math.BigInteger(1, raw.reversedArray())),
        )

    /**
     * Sealed-box: [ephemeralPubRaw 32B][nonce 12B][ciphertext+tag 16B]. Sender key material
     * binds via HKDF salt = ephemeralPub || recipientPub; sender authenticity rides on originSig.
     */
    fun seal(recipientPublic: PublicKey, plaintext: ByteArray, rng: SecureRandom = random): ByteArray {
        val eph = newX25519KeyPair()
        val shared = x25519(eph.private, recipientPublic)
        val ephRaw = rawX25519(eph.public)
        val recipientRaw = rawX25519(recipientPublic)
        val okm = hkdfSha256(shared, salt = ephRaw + recipientRaw, info = "mesh-e2e-v1".toByteArray(), length = 44)
        val key = okm.copyOfRange(0, 32)
        val nonce = okm.copyOfRange(32, 44)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(32 + 12 + ct.size)
        ephRaw.copyInto(out, 0)
        nonce.copyInto(out, 32)
        ct.copyInto(out, 44)
        return out
    }

    fun open(recipientPrivate: PrivateKey, senderPublic: PublicKey, sealed: ByteArray): ByteArray? = try {
        require(sealed.size > 44)
        val ephRaw = sealed.copyOfRange(0, 32)
        val nonce = sealed.copyOfRange(32, 44)
        val ct = sealed.copyOfRange(44, sealed.size)
        val ephPub = x25519PublicFromRaw(ephRaw)
        val shared = x25519(recipientPrivate, ephPub)
        // salt must match seal(): ephemeralPub || recipientPub (recipient pub derived from own private)
        val recipientRaw = rawX25519(x25519PublicFromPrivate(recipientPrivate))
        val okm = hkdfSha256(shared, salt = ephRaw + recipientRaw, info = "mesh-e2e-v1".toByteArray(), length = 44)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(okm.copyOfRange(0, 32), "ChaCha20"), IvParameterSpec(nonce))
        cipher.doFinal(ct)
    } catch (_: Exception) {
        null
    }

    fun rawX25519(publicKey: PublicKey): ByteArray {
        // XECPublicKey.getU() -> unsigned big-endian magnitude; wire format is 32B little-endian
        val u = (publicKey as java.security.interfaces.XECPublicKey).u
        val bytes = u.toByteArray()
        val trimmed = if (bytes.size > 32 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        val out = ByteArray(32)
        val rev = trimmed.reversedArray()
        rev.copyInto(out, 32 - rev.size)
        return out
    }

    /** Raw 32-byte little-endian Y (sign bit in MSB) encoding of an Ed25519 public key. */
    fun rawEd25519(publicKey: PublicKey): ByteArray {
        val point = (publicKey as java.security.interfaces.EdECPublicKey).point
        val y = point.y.toByteArray()
        val trimmed = if (y.size > 32 && y[0] == 0.toByte()) y.copyOfRange(1, y.size) else y
        val out = ByteArray(32)
        trimmed.reversedArray().copyInto(out, 32 - trimmed.size)
        out[31] = ((out[31].toInt() and 0x7F) or (if (point.isXOdd) 0x80 else 0)).toByte()
        return out
    }

    // ---------- originId rotation + hop MAC ----------

    fun rotatingOriginId(deviceSecret: ByteArray, epoch: Int): ByteArray =
        hmacSha256(deviceSecret, ("mesh-origin-id|$epoch").toByteArray()).copyOfRange(0, 8)

    fun hopMac(linkKey: ByteArray, hopSection: ByteArray): ByteArray =
        hmacSha256(linkKey, hopSection).copyOfRange(0, 16)

    // ---------- primitives ----------

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(32, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

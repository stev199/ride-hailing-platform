package com.ridehailing.mesh

import com.ridehailing.mesh.crypto.MeshCrypto
import java.security.PrivateKey
import java.security.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {

    // ---------- RFC 8032 test 1 (Ed25519 known-answer) ----------
    // seed -> signature over empty message, verified against the RFC-published vector.
    private val rfc8032Seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val rfc8032Sig =
        hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")

    // ---------- RFC 8439 2.8.2 (ChaCha20-Poly1305 known-answer) ----------
    private val rfc8439Key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val rfc8439Nonce = hex("070000004041424344454647")
    private val rfc8439Aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val rfc8439Ct =
        hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "bad hex length ${s.length}" }
        return ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
    }

    @Test
    fun `ed25519 known-answer - rfc8032 test 1 empty message`() {
        val priv = MeshCrypto.ed25519PrivateKeyFromSeed(rfc8032Seed)
        val sig = MeshCrypto.sign(priv, ByteArray(0))
        assertTrue(rfc8032Sig.contentEquals(sig), "signature must match RFC 8032 test 1 vector")
    }

    @Test
    fun `ed25519 verify rejects tampered content`() {
        val kp = MeshCrypto.newIdentity()
        val sig = MeshCrypto.sign(kp.private as PrivateKey, "trip-data".toByteArray())
        assertTrue(MeshCrypto.verify(kp.public, "trip-data".toByteArray(), sig))
        assertTrue(!MeshCrypto.verify(kp.public, "trip-data!".toByteArray(), sig))
    }

    @Test
    fun `raw ed25519 key import roundtrip`() {
        val kp = MeshCrypto.newIdentity()
        val rawPub = MeshCrypto.rawEd25519(kp.public)
        assertEquals(32, rawPub.size)
        val pub = MeshCrypto.ed25519PublicKeyFromRaw(rawPub)
        val sig = MeshCrypto.sign(kp.private as PrivateKey, "x".toByteArray())
        assertTrue(MeshCrypto.verify(pub, "x".toByteArray(), sig))
    }

    @Test
    fun `chacha20poly1305 known-answer - rfc8439 2_8_2`() {
        // Reconstruct via JDK and compare against the RFC vector (encrypt path)
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(rfc8439Key, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(rfc8439Nonce),
        )
        cipher.updateAAD(rfc8439Aad)
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one " +
            "tip for the future, sunscreen would be it.").toByteArray()
        val ct = cipher.doFinal(pt)
        assertTrue(rfc8439Ct.contentEquals(ct), "ciphertext+tag must match RFC 8439 2.8.2")
    }

    @Test
    fun `sealed box roundtrip and tamper rejection`() {
        val alice = MeshCrypto.newX25519KeyPair()
        val bob = MeshCrypto.newX25519KeyPair()
        val secret = "ride request payload".toByteArray()
        val sealed = MeshCrypto.seal(bob.public, secret)
        val opened = MeshCrypto.open(bob.private, alice.public, sealed)
        assertNotNull(opened)
        assertTrue(secret.contentEquals(opened))
        assertNull(MeshCrypto.open(alice.private, alice.public, sealed)) // wrong recipient
        val flipped = sealed.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(MeshCrypto.open(bob.private, alice.public, flipped)) // AEAD tag breaks
    }

    @Test
    fun `x25519 ecdh is symmetric`() {
        val a = MeshCrypto.newX25519KeyPair()
        val b = MeshCrypto.newX25519KeyPair()
        val s1 = MeshCrypto.x25519(a.private, b.public)
        val s2 = MeshCrypto.x25519(b.private, a.public)
        assertTrue(s1.contentEquals(s2))
        assertNotEquals(s1.toList(), MeshCrypto.x25519(MeshCrypto.newX25519KeyPair().private, b.public).toList())
    }

    @Test
    fun `origin id rotates deterministically per epoch`() {
        val secret = ByteArray(32) { it.toByte() }
        val id1 = MeshCrypto.rotatingOriginId(secret, 1)
        val id1Again = MeshCrypto.rotatingOriginId(secret, 1)
        val id2 = MeshCrypto.rotatingOriginId(secret, 2)
        assertEquals(id1.toList(), id1Again.toList())
        assertNotEquals(id1.toList(), id2.toList())
        assertEquals(8, id1.size)
    }

    @Test
    fun `hkdf produces stable derived material`() {
        val ikm = ByteArray(32) { (it * 5).toByte() }
        val a = MeshCrypto.hkdfSha256(ikm, salt = "s".toByteArray(), info = "mesh-e2e-v1".toByteArray(), length = 44)
        val b = MeshCrypto.hkdfSha256(ikm, salt = "s".toByteArray(), info = "mesh-e2e-v1".toByteArray(), length = 44)
        assertTrue(a.contentEquals(b))
        assertEquals(44, a.size)
    }
}

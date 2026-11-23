package com.ridehailing.backend

import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.events.EventChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SignedConfigBundleTest {
    private val configJson = EventChain.canonicalJson(
        mapOf("maxHops" to 3L, "ttlSec" to 120L, "channels" to listOf("sms", "email")),
    )

    @Test
    fun `sign and verify roundtrip`() {
        val bundle = SignedConfigBundle.inMemoryDevKeys()
        val signed = bundle.sign(configJson, version = 5, issuedAtSec = 1000)
        assertNotNull(signed)
        assertEquals(SignedConfigBundle.VerifyResult.Valid, bundle.verify(signed, minVersion = 1))
    }

    @Test
    fun `tampered config or signature fails`() {
        val bundle = SignedConfigBundle.inMemoryDevKeys()
        val signed = bundle.sign(configJson, version = 5, issuedAtSec = 1000)!!
        assertEquals(
            SignedConfigBundle.VerifyResult.BadSignature,
            bundle.verify(signed.copy(configJson = configJson.replace("3", "9")), minVersion = 1),
        )
        val flipped = signed.signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(
            SignedConfigBundle.VerifyResult.BadSignature,
            bundle.verify(signed.copy(signature = flipped), minVersion = 1),
        )
    }

    @Test
    fun `anti-rollback rejects versions at or below the floor`() {
        val bundle = SignedConfigBundle.inMemoryDevKeys()
        val signed = bundle.sign(configJson, version = 5, issuedAtSec = 1000)!!
        assertEquals(SignedConfigBundle.VerifyResult.RolledBack, bundle.verify(signed, minVersion = 5))
        assertEquals(SignedConfigBundle.VerifyResult.RolledBack, bundle.verify(signed, minVersion = 6))
        assertEquals(SignedConfigBundle.VerifyResult.Valid, bundle.verify(signed, minVersion = 4))
    }

    @Test
    fun `signing impossible without private key`() {
        val pub = SignedConfigBundle.generateKeyPair().public
        val verifier = SignedConfigBundle.fromKeys(pub.encoded, null)
        assertNull(verifier.sign(configJson, version = 1, issuedAtSec = 1))
    }

    @Test
    fun `ed25519 signature is deterministic (known-answer)`() {
        val kp = SignedConfigBundle.generateKeyPair()
        val bundle = SignedConfigBundle.fromKeys(kp.public.encoded, kp.private.encoded)
        val s1 = bundle.sign(configJson, version = 1, issuedAtSec = 1)!!
        val s2 = bundle.sign(configJson, version = 1, issuedAtSec = 1)!!
        assertEquals(s1.signature.toList(), s2.signature.toList())
    }
}

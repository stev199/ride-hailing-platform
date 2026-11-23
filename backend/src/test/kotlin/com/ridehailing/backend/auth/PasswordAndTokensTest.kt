package com.ridehailing.backend.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordServiceTest {
    private val service = PasswordService()

    @Test
    fun `hash and verify roundtrip`() {
        val hash = service.hash("correct-horse-battery")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=65536,t=3,p=1\$"), "PHC string with argon2id v19")
        assertTrue(service.verify("correct-horse-battery", hash))
        assertFalse(service.verify("wrong-horse-battery", hash))
    }

    @Test
    fun `same password hashes differ (random salt)`() {
        assertNotEquals(service.hash("correct-horse-battery"), service.hash("correct-horse-battery"))
    }

    @Test
    fun `malformed stored hashes verify false instead of throwing`() {
        assertFalse(service.verify("correct-horse-battery", ""))
        assertFalse(service.verify("correct-horse-battery", "not-a-hash"))
        assertFalse(service.verify("correct-horse-battery", "\$argon2i\$v=19\$m=1,t=1,p=1\$salt\$hash"))
        assertFalse(service.verify("correct-horse-battery", "\$argon2id\$v=16\$m=65536,t=3,p=1\$\$\$"))
    }

    @Test
    fun `policy rejects short common and all-digit passwords`() {
        assertEquals("password_too_short", service.policyViolation("short"))
        assertEquals("password_too_common", service.policyViolation("Password123"))
        assertEquals("password_all_digits", service.policyViolation("12345678901"))
        assertNull(service.policyViolation("aReasonable-pass-2026"))
    }

    @Test
    fun `hash refuses too-short passwords`() {
        val caught = runCatching { service.hash("short") }
        assertTrue(caught.isFailure, "hash must reject policy-violating input")
    }
}

class RefreshTokenStoreTest {
    private fun service() = JwtService()

    @Test
    fun `refresh rotates and old token cannot be replayed (reuse detected revokes family)`() {
        val jwt = service()
        val now = java.time.Instant.now()
        val pair1 = jwt.issueSession("user-1", "RIDER", now)
        val r1 = jwt.refresh(pair1.refreshToken, now.plusSeconds(60))
        assertTrue(r1 is JwtService.RefreshResult.Rotated)
        val pair2 = (r1 as JwtService.RefreshResult.Rotated).pair
        // Reuse of the just-rotated token must be detected and revoke the family.
        assertEquals(JwtService.RefreshResult.ReuseDetected, jwt.refresh(pair1.refreshToken, now.plusSeconds(90)))
        // Even the "valid" new generation is now dead (family revoked).
        assertEquals(JwtService.RefreshResult.Invalid, jwt.refresh(pair2.refreshToken, now.plusSeconds(120)))
    }

    @Test
    fun `unknown and revoked tokens are invalid`() {
        val jwt = service()
        assertEquals(JwtService.RefreshResult.Invalid, jwt.refresh("garbage"))
        val pair = jwt.issueSession("u", "RIDER")
        assertTrue(jwt.revokeSession(pair.refreshToken))
        assertEquals(JwtService.RefreshResult.Invalid, jwt.refresh(pair.refreshToken))
    }

    @Test
    fun `access token verifies and rejects tampering`() {
        val jwt = JwtService("a-sufficiently-long-secret-32-bytes-min!!".toByteArray())
        val token = jwt.issueAccess("user-9", "RIDER")
        val verifier = jwt.buildVerifier()
        assertEquals("user-9", verifier.verify(token).subject)
        val tampered = token.dropLast(3) + (if (token.takeLast(3) == "aaa") "bbb" else "aaa")
        runCatching { verifier.verify(tampered) }.onSuccess { assertTrue(false, "tampered token must fail") }
    }

    @Test
    fun `access token carries role and typ claims`() {
        val jwt = service()
        val token = jwt.issueAccess("user-9", "DRIVER")
        val claims = jwt.buildVerifier().verify(token)
        assertEquals("DRIVER", claims.getClaim("role").asString())
        assertEquals("access", claims.getClaim("typ").asString())
    }

    @Test
    fun `expired access token is rejected by the verifier`() {
        val jwt = JwtService("a-sufficiently-long-secret-32-bytes-min!!".toByteArray(), -1)
        val token = jwt.issueAccess("user-9", "RIDER")
        runCatching { jwt.buildVerifier().verify(token) }.onSuccess { assertTrue(false, "expired token must fail") }
    }
}

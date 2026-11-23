package com.ridehailing.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Token service (B-04): short-lived HS256 access JWT + rotating refresh tokens.
 *
 * Access token claims: sub (user id), role, typ="access", iat, exp. Secret comes from
 * env AUTH_JWT_SECRET (>= 32 bytes of entropy); dev/test fall back to a per-process
 * random secret, which makes all previously issued tokens invalid across restarts -
 * acceptable for dev, and production MUST inject the real secret.
 *
 * Refresh tokens are opaque 256-bit random strings. Only a SHA-256 hash is stored.
 * Rotation: every refresh returns a NEW refresh token (same family) and invalidates
 * the presented one. Reuse of an already-rotated token revokes the entire family
 * (stolen-token detection, B-04 "rotating refresh").
 */
class JwtService(
    val secret: ByteArray = resolveSecret(),
    val accessTtlSec: Long = DEFAULT_ACCESS_TTL_SEC,
    private val random: SecureRandom = SecureRandom(),
) {

    data class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresAtEpochSec: Long)

    private val algorithm = Algorithm.HMAC256(secret)
    private val refreshStore = RefreshTokenStore(random)

    companion object {
        const val DEFAULT_ACCESS_TTL_SEC = 15L * 60
        private const val REFRESH_TTL_SEC = 30L * 24 * 3600
        const val ENV_SECRET = "AUTH_JWT_SECRET"

        private fun resolveSecret(): ByteArray =
            System.getenv(ENV_SECRET)?.let { env ->
                require(env.toByteArray(Charsets.UTF_8).size >= 32) {
                    "$ENV_SECRET must be at least 32 bytes"
                }
                env.toByteArray(Charsets.UTF_8)
            } ?: ByteArray(48).also { SecureRandom().nextBytes(it) }
    }

    fun buildVerifier(): JWTVerifier = JWT.require(algorithm)
        .withClaim("typ", "access")
        .build()

    /** Issue a fresh access+refresh pair for a new session (new refresh family). */
    fun issueSession(userId: String, role: String, now: Instant = Instant.now()): TokenPair =
        TokenPair(
            accessToken = issueAccess(userId, role, now),
            refreshToken = refreshStore.createFamily(userId, role, now),
            accessExpiresAtEpochSec = now.epochSecond + accessTtlSec,
        )

    fun issueAccess(userId: String, role: String, now: Instant = Instant.now()): String =
        JWT.create()
            .withSubject(userId)
            .withClaim("role", role)
            .withClaim("typ", "access")
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(accessTtlSec))
            .sign(algorithm)

    sealed class RefreshResult {
        data class Rotated(val pair: TokenPair) : RefreshResult()
        data object ReuseDetected : RefreshResult()
        data object Invalid : RefreshResult()
    }

    /**
     * Rotate a refresh token. Reuse (presenting a token that was already consumed)
     * revokes the whole family; every result path is O(1) in stored sessions.
     */
    fun refresh(presentedToken: String, now: Instant = Instant.now()): RefreshResult =
        when (val r = refreshStore.rotate(presentedToken, REFRESH_TTL_SEC, now)) {
            is RefreshTokenStore.RotateResult.Reused -> RefreshResult.ReuseDetected
            is RefreshTokenStore.RotateResult.Invalid -> RefreshResult.Invalid
            is RefreshTokenStore.RotateResult.Rotated -> RefreshResult.Rotated(
                TokenPair(
                    accessToken = issueAccess(r.userId, r.role, now),
                    refreshToken = r.newToken,
                    accessExpiresAtEpochSec = now.epochSecond + accessTtlSec,
                ),
            )
        }

    fun revokeSession(refreshToken: String): Boolean = refreshStore.revoke(refreshToken)

    /** Test/ops hook: number of live refresh tokens (families x generation). */
    fun liveRefreshTokenCount(): Int = refreshStore.liveCount()
}

/**
 * In-memory refresh-token store keyed by token hash. Family = one login session;
 * rotation replaces the stored entry with a new generation, keeping the family id.
 * Reuse detection: a consumed token hash stays recorded (tombstoned with its family)
 * until the family is revoked, so presenting it again is a positive signal.
 */
class RefreshTokenStore(private val random: SecureRandom) {

    private class Entry(
        val familyId: String,
        val userId: String,
        val role: String,
        val expiresAtSec: Long,
        var consumed: Boolean = false,
    )

    private val tokens = ConcurrentHashMap<String, Entry>() // key = SHA-256(token) hex
    private val revokedFamilies = ConcurrentHashMap.newKeySet<String>()

    private fun hash(token: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun createFamily(userId: String, role: String, now: Instant): String {
        val familyId = newToken()
        val token = newToken()
        tokens[hash(token)] = Entry(familyId, userId, role, now.epochSecond + 30L * 24 * 3600)
        return token
    }

    sealed class RotateResult {
        data class Rotated(val userId: String, val role: String, val newToken: String) : RotateResult()
        data object Reused : RotateResult()
        data object Invalid : RotateResult()
    }

    fun rotate(presentedToken: String, ttlSec: Long, now: Instant): RotateResult {
        val key = hash(presentedToken)
        val entry = tokens[key] ?: return RotateResult.Invalid
        if (entry.familyId in revokedFamilies) return RotateResult.Invalid
        if (entry.expiresAtSec <= now.epochSecond) {
            tokens.remove(key)
            return RotateResult.Invalid
        }
        if (entry.consumed) {
            // Reuse of a rotated token: the session is assumed compromised - revoke the family.
            revokedFamilies.add(entry.familyId)
            tokens.entries.removeIf { it.value.familyId == entry.familyId }
            return RotateResult.Reused
        }
        entry.consumed = true
        val newToken = newToken()
        tokens[hash(newToken)] = Entry(entry.familyId, entry.userId, entry.role, now.epochSecond + ttlSec)
        // Tombstone cleanup: consumed entries are kept for reuse detection but pruned
        // when their family reaches >= 8 generations (bounded memory, D-safe default).
        tokens.entries.removeIf {
            it.value.familyId == entry.familyId && it.value.consumed && it.key != key && it.key != hash(newToken)
        }
        return RotateResult.Rotated(entry.userId, entry.role, newToken)
    }

    fun revoke(token: String): Boolean {
        val key = hash(token)
        val entry = tokens[key] ?: return false
        revokedFamilies.add(entry.familyId)
        tokens.entries.removeIf { it.value.familyId == entry.familyId }
        return true
    }

    fun liveCount(): Int = tokens.count { !it.value.consumed }
}

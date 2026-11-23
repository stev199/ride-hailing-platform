package com.ridehailing.backend.auth

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Password hashing (B-04 security bar): argon2id with per-password random salt.
 *
 * Encoded form: `$argon2id$v=19$m=65536,t=3,p=1$<saltB64url>$<hashB64url>`
 * (PHC string format, self-describing so future parameter upgrades verify old hashes).
 *
 * Memory 64 MiB, 3 iterations, parallelism 1 - moderate OWASP-recommended baseline;
 * server-side so memory cost does not punish low-end clients.
 */
class PasswordService(private val random: SecureRandom = SecureRandom()) {

    data class Params(val memoryKib: Int, val iterations: Int, val parallelism: Int)

    companion object {
        const val MIN_PASSWORD_LENGTH = 10
        const val MAX_PASSWORD_LENGTH = 128
        val DEFAULT_PARAMS = Params(memoryKib = 65_536, iterations = 3, parallelism = 1)

        // Policy note (D44/D49): the server enforces ONLY the documented length rule so the
        // OpenAPI contract stays exactly honest (schemathesis positive/negative checks).
        // Common-password rejection is enforced at the API-consumer layer (apps/admin) and can be
        // extended server-side in lockstep with the schema when P1-3 lands.
    }

    fun hash(password: String, params: Params = DEFAULT_PARAMS): String {
        require(password.length >= MIN_PASSWORD_LENGTH) { "password too short" }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = argon2id(password, salt, params)
        return "\$argon2id\$v=19\$m=${params.memoryKib},t=${params.iterations},p=${params.parallelism}" +
            "\$${b64url(salt)}\$${b64url(hash)}"
    }

    /**
     * Constant-time verification against a stored PHC string. Malformed/unrecognized
     * stored hashes verify as false (never throw to the caller).
     */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$').filter { it.isNotBlank() }
        // ["argon2id", "v=19", "m=65536,t=3,p=1", salt, hash]
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val version = parts[1].removePrefix("v=").toIntOrNull() ?: return false
        if (version != 19) return false
        val params = parts[2].split(',').mapNotNull {
            val kv = it.split('=')
            if (kv.size != 2) return@mapNotNull null
            kv[0] to (kv[1].toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        val memory = params["m"] ?: return false
        val iterations = params["t"] ?: return false
        val parallelism = params["p"] ?: return false
        val salt = try {
            fromB64url(parts[3])
        } catch (_: Exception) {
            return false
        }
        val expected = try {
            fromB64url(parts[4])
        } catch (_: Exception) {
            return false
        }
        val actual = argon2id(password, salt, Params(memory, iterations, parallelism))
        return MessageDigest.isEqual(actual, expected)
    }

    /** Validate password policy; returns a machine-readable rejection or null when acceptable.
     *  Bounds match openapi RegisterRequest.password (minLength 10, maxLength 128, D49). */
    fun policyViolation(password: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "password_too_short"
        password.length > MAX_PASSWORD_LENGTH -> "password_too_long"
        else -> null
    }

    private fun argon2id(password: String, salt: ByteArray, params: Params): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKib)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(32)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }

    private fun b64url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fromB64url(s: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(s)
}

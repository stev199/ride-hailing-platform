package com.ridehailing.backend.auth

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** User roles as in the V1 schema CHECK constraint. */
enum class Role {
    RIDER, DRIVER,
    ADMIN_SUPER, ADMIN_OPS, ADMIN_SUPPORT, ADMIN_FINANCE, ADMIN_VERIFIER, ADMIN_READONLY;

    val isAdmin: Boolean get() = this in ADMIN_ROLES

    companion object {
        val ADMIN_ROLES = setOf(ADMIN_SUPER, ADMIN_OPS, ADMIN_SUPPORT, ADMIN_FINANCE, ADMIN_VERIFIER, ADMIN_READONLY)
        fun fromStored(raw: String): Role? = entries.firstOrNull { it.name == raw }
    }
}

enum class UserStatus { ACTIVE, BANNED_TEMP, BANNED_PERM, DELETED }

/**
 * User aggregate (P1-2). `phone`/`email` are normalized unique identifiers
 * (E.164-ish digits-only phone, lowercased trimmed email). `passwordHash` is
 * argon2id PHC or null for OTP-only accounts.
 */
data class User(
    val id: String,
    val phone: String?,
    val email: String?,
    val passwordHash: String?,
    val displayName: String,
    val role: Role,
    val status: UserStatus = UserStatus.ACTIVE,
    val locale: String = "en",
    val createdAtEpochSec: Long,
)

/**
 * Persistence port for users (P1-2). The in-memory implementation backs the current
 * stage (backend skeleton has no runtime DB wiring yet - see STATE degraded notes);
 * the Postgres implementation lands with the P1-3/P1-5 user+config services and the
 * schema is already in Flyway V1. Storage engines MUST treat phone/email/password
 * the same as this port does: normalized, unique, hash-only for passwords.
 */
interface UserStore {
    fun findById(id: String): User?
    fun findByPhone(phone: String): User?
    fun findByEmail(email: String): User?
    fun insert(user: User): User
    fun update(user: User): User

    /**
     * Admin search (P1-3): case-insensitive substring match over phone, email and
     * displayName; null query = list all. Deterministic order (creation id asc).
     */
    fun search(query: String?, limit: Int, offset: Int): List<User>
}

class InMemoryUserStore : UserStore {
    private val byId = ConcurrentHashMap<String, User>()

    override fun findById(id: String): User? = byId[id]
    override fun findByPhone(phone: String): User? = byId.values.firstOrNull { it.phone == phone }
    override fun findByEmail(email: String): User? = byId.values.firstOrNull { it.email == email }
    override fun insert(user: User): User {
        require(byId.putIfAbsent(user.id, user) == null) { "duplicate user id" }
        return user
    }

    override fun update(user: User): User {
        byId[user.id] = user
        return user
    }

    override fun search(query: String?, limit: Int, offset: Int): List<User> {
        val q = query?.trim()?.lowercase()
        return byId.values
            .sortedBy { it.createdAtEpochSec }
            .filter { u ->
                q == null ||
                    u.phone?.contains(q) == true ||
                    u.email?.contains(q) == true ||
                    u.displayName.lowercase().contains(q)
            }
            .drop(offset)
            .take(limit)
    }
}

/** Public profile shape (never exposes passwordHash; PII minimization). */
data class UserProfile(
    val id: String,
    val phone: String?,
    val email: String?,
    val displayName: String,
    val role: Role,
    val status: UserStatus,
    val locale: String,
)

fun User.profile(): UserProfile = UserProfile(
    id = id, phone = phone, email = email, displayName = displayName,
    role = role, status = status, locale = locale,
)

/**
 * Identifier normalization (D49). These functions are the single source of truth
 * for what the API accepts as phone/email: docs/api/openapi.yaml carries regex
 * patterns that mirror them exactly, and backend ContractAlignmentTest asserts
 * the equivalence so the documented contract and the code cannot drift.
 *
 * Phone: 8-15 ASCII digits; every other character (spaces, dashes, parentheses,
 * a leading +) is ignored, so "+33 1 23-45-67-89" normalizes to 33123456789.
 * Unicode digits are not phone digits (delivery addressing stays ASCII).
 * Email: validated on the RAW string (exactly one '@' with a non-empty local
 * part and a domain not ending in '.'), stored trimmed + lowercased. The stored
 * form is what uniqueness is keyed on.
 */
object Identifiers {
    private val ASCII_DIGITS = '0'..'9'

    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it in ASCII_DIGITS }
        return if (digits.length in 8..15) digits else null
    }

    fun normalizeEmail(raw: String): String? {
        val at = raw.indexOf('@')
        // Exactly one '@', non-empty local part, domain not ending in '.' -
        // mirrors the openapi pattern '^[^@]+@[^@]*[^@.]$' in both directions (D49).
        val ok = at > 0 && at < raw.length - 1 && raw.last() != '.' && raw.indexOf('@', at + 1) == -1
        return if (ok) raw.trim().lowercase() else null
    }
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}

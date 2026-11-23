package com.ridehailing.backend.rbac

import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.JwtService
import com.ridehailing.backend.auth.PasswordService
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.auth.UserStore

/**
 * Admin user management (P1-3). Every mutation appends an audit event to the
 * hash-chained event store (spec: "immutable audit log"; admin actions section
 * "admin actions in audit store"). Audit payloads are PII-minimal: identifiers
 * and ids, never password hashes or tokens.
 *
 * Guards beyond RBAC:
 *  - only ADMIN_SUPER may mint or change ADMIN_* roles (no privilege escalation
 *    by OPS via the USERS_WRITE permission);
 *  - an admin cannot change their own role or status, and cannot force-logout
 *    themselves (accidental self-lockout protection);
 *  - unknown target user -> 404 at the route layer.
 */
class AdminService(
    private val users: UserStore,
    private val passwords: PasswordService,
    private val tokens: JwtService,
    private val eventRecorder: AuthService.EventChainRecorder,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {

    data class UpdateFields(
        val displayName: String? = null,
        val locale: String? = null,
        val status: UserStatus? = null,
        val role: Role? = null,
    )

    sealed class CreateResult {
        data class Created(val user: User) : CreateResult()
        data class Invalid(val field: String, val code: String) : CreateResult()
        data object Duplicate : CreateResult()
        data object Forbidden : CreateResult()
    }

    sealed class UpdateResult {
        data class Updated(val user: User) : UpdateResult()
        data class Invalid(val field: String, val code: String) : UpdateResult()
        data object NotFound : UpdateResult()
        data object Duplicate : UpdateResult()
        data object Forbidden : UpdateResult()
    }

    fun search(query: String?, limit: Int, offset: Int): List<User> =
        users.search(query, limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    fun findById(id: String): User? = users.findById(id)

    fun createUser(actor: User, displayName: String, phone: String?, email: String?, role: Role, password: String?, locale: String?): CreateResult {
        if (role.isAdmin && actor.role != Role.ADMIN_SUPER) return CreateResult.Forbidden
        val name = displayName.trim(' ', '\t', '\n', '\u000B', '\u000C', '\r')
        if (name.length !in 2..64 || name.isEmpty()) return CreateResult.Invalid("displayName", "invalid_display_name")
        val normalizedPhone = phone?.let { com.ridehailing.backend.auth.Identifiers.normalizePhone(it) }
            ?: if (phone != null) return CreateResult.Invalid("phone", "invalid_phone") else null
        val normalizedEmail = email?.let { com.ridehailing.backend.auth.Identifiers.normalizeEmail(it) }
            ?: if (email != null) return CreateResult.Invalid("email", "invalid_email") else null
        if (normalizedPhone == null && normalizedEmail == null) return CreateResult.Invalid("identifier", "identifier_required")
        if (normalizedPhone != null && users.findByPhone(normalizedPhone) != null) return CreateResult.Duplicate
        if (normalizedEmail != null && users.findByEmail(normalizedEmail) != null) return CreateResult.Duplicate
        val hash = password?.let {
            val v = passwords.policyViolation(it)
            if (v != null) return CreateResult.Invalid("password", v)
            passwords.hash(it)
        }
        val now = clock()
        val user = User(
            id = Ids.newId(),
            phone = normalizedPhone,
            email = normalizedEmail,
            passwordHash = hash,
            displayName = name,
            role = role,
            status = UserStatus.ACTIVE,
            locale = locale?.takeIf { it in setOf("en", "fr", "ar") } ?: "en",
            createdAtEpochSec = now.epochSecond,
        )
        users.insert(user)
        eventRecorder.append(
            "admin.user.created",
            mapOf(
                "actorId" to actor.id,
                "userId" to user.id,
                "role" to user.role.name,
                "identifier" to (normalizedPhone ?: normalizedEmail),
            ),
        )
        return CreateResult.Created(user)
    }

    fun updateUser(actor: User, targetId: String, fields: UpdateFields): UpdateResult {
        val target = users.findById(targetId) ?: return UpdateResult.NotFound
        if (fields.role != null && fields.role.isAdmin && actor.role != Role.ADMIN_SUPER) {
            return UpdateResult.Forbidden
        }
        if (target.id == actor.id && (fields.role != null || fields.status != null)) {
            return UpdateResult.Forbidden // no self role/status change (lockout protection)
        }
        val name = fields.displayName?.trim(' ', '\t', '\n', '\u000B', '\u000C', '\r')
        if (name != null && (name.length !in 2..64 || name.isEmpty())) {
            return UpdateResult.Invalid("displayName", "invalid_display_name")
        }
        if (fields.locale != null && fields.locale !in setOf("en", "fr", "ar")) {
            return UpdateResult.Invalid("locale", "invalid_locale")
        }
        val before = target
        val updated = target.copy(
            displayName = name ?: target.displayName,
            locale = fields.locale ?: target.locale,
            status = fields.status ?: target.status,
            role = fields.role ?: target.role,
        )
        users.update(updated)
        val changes = buildMap<String, String?> {
            put("actorId", actor.id)
            put("userId", target.id)
            if (before.displayName != updated.displayName) put("displayName", updated.displayName)
            if (before.locale != updated.locale) put("locale", updated.locale)
            if (before.status != updated.status) {
                put("statusBefore", before.status.name)
                put("statusAfter", updated.status.name)
            }
            if (before.role != updated.role) {
                put("roleBefore", before.role.name)
                put("roleAfter", updated.role.name)
            }
        }
        eventRecorder.append("admin.user.updated", changes)
        return UpdateResult.Updated(updated)
    }

    sealed class ResetResult {
        data class Reset(val user: User) : ResetResult()
        data object NotFound : ResetResult()
        data class Invalid(val field: String, val code: String) : ResetResult()
    }

    /** Admin password reset: sets a temp password; audit carries NO hash (PII minimization). */
    fun resetPassword(actor: User, targetId: String, newPassword: String): ResetResult {
        val target = users.findById(targetId) ?: return ResetResult.NotFound
        val violation = passwords.policyViolation(newPassword)
        if (violation != null) return ResetResult.Invalid("password", violation)
        users.update(target.copy(passwordHash = passwords.hash(newPassword)))
        // Force logout: a reset password must not leave the old sessions alive (B-04).
        tokens.revokeAllSessionsOf(target.id)
        eventRecorder.append(
            "admin.password_reset",
            mapOf("actorId" to actor.id, "userId" to target.id, "sessionsRevoked" to "all"),
        )
        return ResetResult.Reset(target)
    }

    sealed class ForceLogoutResult {
        data class Done(val revokedSessions: Int) : ForceLogoutResult()
        data object NotFound : ForceLogoutResult()
        data object Forbidden : ForceLogoutResult()
    }

    fun forceLogout(actor: User, targetId: String): ForceLogoutResult {
        if (actor.id == targetId) return ForceLogoutResult.Forbidden // no self force-logout
        val target = users.findById(targetId) ?: return ForceLogoutResult.NotFound
        val revoked = tokens.revokeAllSessionsOf(target.id)
        eventRecorder.append(
            "admin.force_logout",
            mapOf("actorId" to actor.id, "userId" to target.id, "sessionsRevoked" to revoked.toString()),
        )
        return ForceLogoutResult.Done(revoked)
    }
}

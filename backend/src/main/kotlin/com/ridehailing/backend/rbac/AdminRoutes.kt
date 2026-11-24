package com.ridehailing.backend.rbac

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.auth.withUser
import com.ridehailing.backend.events.EventChain
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ---------- DTOs (mirrored in docs/api/openapi.yaml) ----------

@Serializable
data class AdminCreateUserRequest(
    val displayName: String,
    val role: String,
    val phone: String? = null,
    val email: String? = null,
    val password: String? = null,
    val locale: String? = null,
)

@Serializable
data class AdminPatchUserRequest(
    val displayName: String? = null,
    val locale: String? = null,
    val status: String? = null,
    val role: String? = null,
)

@Serializable
data class AdminResetPasswordRequest(val newPassword: String)

@Serializable
data class AdminUserDto(
    val id: String,
    val phone: String?,
    val email: String?,
    val displayName: String,
    val role: String,
    val status: String,
    val locale: String,
    val createdAtEpochSec: Long,
)

@Serializable
data class AdminUserListDto(val users: List<AdminUserDto>, val limit: Int, val offset: Int)

@Serializable
data class SessionDto(
    val familyId: String,
    val deviceId: String?,
    val createdAtEpochSec: Long,
    val lastRotatedEpochSec: Long,
)

@Serializable
data class SessionListDto(val sessions: List<SessionDto>)

@Serializable
data class AuditEventDto(val seq: Long, val type: String, val occurredAt: String, val payloadJson: String, val hash: String)

@Serializable
data class AuditTailDto(val verifyOk: Boolean, val events: List<AuditEventDto>)

private fun User.toDto() = AdminUserDto(
    id = id, phone = phone, email = email, displayName = displayName,
    role = role.name, status = status.name, locale = locale, createdAtEpochSec = createdAtEpochSec,
)

private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/**
 * Permission guard (P1-3): 401 for missing/invalid tokens (auth), 403 for valid
 * tokens of the wrong role (authorization) - never merged (B-04).
 */
internal suspend fun ApplicationCall.withPermission(service: AuthService, perm: Perm, handler: suspend (User) -> Unit) {
    withUser(service) { user ->
        if (Rbac.can(user.role, perm)) {
            handler(user)
        } else {
            respond(HttpStatusCode.Forbidden, err("forbidden", "Role ${user.role.name} lacks permission ${perm.name}"))
        }
    }
}

private fun parseStatus(raw: String): UserStatus? =
    UserStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

private fun parseRole(raw: String): Role? = Role.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

/**
 * P1-CONTRACT: admin list pagination must match the openapi query schemas
 * (D49 generator-precision): limit in 1..maxLimit, offset >= 0. Out-of-range
 * = 400 invalid_pagination (schemathesis ST201 caught the silent clamp/accept).
 * Returns null after responding when the parameters violate the contract.
 */
private suspend fun ApplicationCall.paginationOr400(defaultLimit: Int, maxLimit: Int): Pair<Int, Int>? {
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: defaultLimit
    val offset = request.queryParameters["offset"]?.toIntOrNull() ?: 0
    if (limit < 1 || limit > maxLimit || offset < 0) {
        respond(HttpStatusCode.BadRequest, err("invalid_pagination", "limit must be 1..$maxLimit, offset must be >= 0"))
        return null
    }
    return limit to offset
}

/**
 * P1-3 admin routes: user management with audited mutations, audit tail, session
 * admin, and the user's own session list. Every mutation goes through AdminService
 * which appends to the hash-chained event store.
 */
fun Route.adminRoutes(service: AuthService, admin: AdminService, eventChain: EventChain) {
    route("/v1/admin") {
        get("/users") {
            call.withPermission(service, Perm.USERS_READ) {
                val query = call.request.queryParameters["query"]
                val (limit, offset) = call.paginationOr400(defaultLimit = 20, maxLimit = 100)
                    ?: return@withPermission
                val users = admin.search(query, limit, offset)
                call.respond(HttpStatusCode.OK, AdminUserListDto(users.map { it.toDto() }, limit, offset))
            }
        }
        post("/users") {
            call.withPermission(service, Perm.USERS_WRITE) { actor ->
                val req = call.receive<AdminCreateUserRequest>()
                val role = parseRole(req.role) ?: return@withPermission call.respond(
                    HttpStatusCode.BadRequest, err("invalid_role", "Unknown role"),
                )
                when (val r = admin.createUser(actor, req.displayName, req.phone, req.email, role, req.password, req.locale)) {
                    is AdminService.CreateResult.Created -> call.respond(HttpStatusCode.Created, r.user.toDto())
                    is AdminService.CreateResult.Duplicate ->
                        call.respond(HttpStatusCode.Conflict, err("identifier_taken", "Phone or email already registered"))
                    is AdminService.CreateResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, err("forbidden", "Only ADMIN_SUPER can create admin roles"))
                    is AdminService.CreateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(r.code, "Validation failed: ${r.field}"))
                }
            }
        }
        get("/users/{id}") {
            call.withPermission(service, Perm.USERS_READ) {
                val user = admin.findById(call.parameters["id"] ?: "")
                if (user == null) call.respond(HttpStatusCode.NotFound, err("not_found", "User not found"))
                else call.respond(HttpStatusCode.OK, user.toDto())
            }
        }
        patch("/users/{id}") {
            call.withPermission(service, Perm.USERS_WRITE) { actor ->
                val req = call.receive<AdminPatchUserRequest>()
                val status = req.status?.let { s ->
                    parseStatus(s) ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest, err("invalid_status", "Unknown status"),
                    )
                }
                val role = req.role?.let { r ->
                    parseRole(r) ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest, err("invalid_role", "Unknown role"),
                    )
                }
                when (val r = admin.updateUser(actor, call.parameters["id"] ?: "", AdminService.UpdateFields(req.displayName, req.locale, status, role))) {
                    is AdminService.UpdateResult.Updated -> call.respond(HttpStatusCode.OK, r.user.toDto())
                    AdminService.UpdateResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, err("not_found", "User not found"))
                    AdminService.UpdateResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, err("forbidden", "Operation not permitted for your role"))
                    is AdminService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(r.code, "Validation failed: ${r.field}"))
                    AdminService.UpdateResult.Duplicate ->
                        call.respond(HttpStatusCode.Conflict, err("identifier_taken", "Identifier already registered"))
                }
            }
        }
        post("/users/{id}/reset-password") {
            call.withPermission(service, Perm.USERS_RESET_PASSWORD) { actor ->
                val req = call.receive<AdminResetPasswordRequest>()
                when (val r = admin.resetPassword(actor, call.parameters["id"] ?: "", req.newPassword)) {
                    is AdminService.ResetResult.Reset -> call.respond(HttpStatusCode.OK, r.user.toDto())
                    AdminService.ResetResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, err("not_found", "User not found"))
                    is AdminService.ResetResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(r.code, "Validation failed: ${r.field}"))
                }
            }
        }
        post("/users/{id}/force-logout") {
            call.withPermission(service, Perm.SESSIONS_ADMIN) { actor ->
                when (val r = admin.forceLogout(actor, call.parameters["id"] ?: "")) {
                    is AdminService.ForceLogoutResult.Done ->
                        call.respond(HttpStatusCode.OK, mapOf("revokedSessions" to r.revokedSessions))
                    AdminService.ForceLogoutResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, err("not_found", "User not found"))
                    AdminService.ForceLogoutResult.Forbidden ->
                        call.respond(HttpStatusCode.BadRequest, err("self_force_logout", "Cannot force-logout yourself"))
                }
            }
        }
        get("/audit") {
            call.withPermission(service, Perm.AUDIT_READ) {
                val (limit, _) = call.paginationOr400(defaultLimit = 50, maxLimit = 500)
                    ?: return@withPermission
                val events = eventChain.snapshot().takeLast(limit)
                call.respond(
                    HttpStatusCode.OK,
                    AuditTailDto(verifyOk = eventChain.verify() == null, events = events.map {
                        AuditEventDto(it.seq, it.type, it.occurredAt, it.payloadJson, it.hash)
                    }),
                )
            }
        }
    }
    // The user's own sessions (P1-3): list + revoke one of yours by family id.
    get("/v1/me/sessions") {
        call.withUser(service) { user ->
            call.respond(
                HttpStatusCode.OK,
                SessionListDto(service.sessionsOf(user.id).map {
                    SessionDto(it.familyId, it.deviceId, it.createdAtEpochSec, it.lastRotatedEpochSec)
                }),
            )
        }
    }
    delete("/v1/me/sessions/{familyId}") {
        call.withUser(service) { user ->
            val familyId = call.parameters["familyId"] ?: ""
            val revoked = service.revokeOwnSession(user.id, familyId)
            if (revoked) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, err("session_not_found", "No such live session for this user"))
        }
    }
}

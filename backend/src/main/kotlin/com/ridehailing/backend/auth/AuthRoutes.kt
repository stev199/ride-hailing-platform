package com.ridehailing.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ---------- request/response DTOs (mirrored in docs/api/openapi.yaml) ----------

@Serializable
data class RegisterRequest(
    val displayName: String,
    val password: String,
    val phone: String? = null,
    val email: String? = null,
    val locale: String? = null,
)

@Serializable
data class PasswordLoginRequest(val identifier: String, val password: String)

@Serializable
data class OtpIssueRequest(
    val channel: String,
    val target: String,
    val locale: String? = null,
)

@Serializable
data class OtpVerifyRequest(
    val requestId: String,
    val code: String,
    val displayName: String? = null,
    val locale: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class TokenPairDto(val accessToken: String, val refreshToken: String, val accessExpiresAtEpochSec: Long)

@Serializable
data class UserDto(
    val id: String,
    val phone: String?,
    val email: String?,
    val displayName: String,
    val role: String,
    val status: String,
    val locale: String,
)

@Serializable
data class AuthSuccessDto(val user: UserDto, val tokens: TokenPairDto, val created: Boolean = false)

@Serializable
data class OtpIssuedDto(val requestId: String, val expiresInSeconds: Long, val resendAfterSeconds: Long)

private fun User.toDto(): UserDto = UserDto(
    id = id, phone = phone, email = email, displayName = displayName,
    role = role.name, status = status.name, locale = locale,
)

private fun JwtService.TokenPair.toDto(): TokenPairDto =
    TokenPairDto(accessToken, refreshToken, accessExpiresAtEpochSec)

private fun err(code: String, message: String) = com.ridehailing.backend.ErrorDto(code = code, message = message)

private fun mapFailureMessage(code: String): String = when (code) {
    "otp_expired" -> "Code expired; request a new one"
    "otp_too_many_attempts" -> "Too many failed attempts; request a new code"
    "otp_invalid" -> "Invalid code"
    "registration_disabled" -> "Registration is currently disabled"
    "refresh_reuse_detected" -> "Refresh token reuse detected; session revoked"
    "refresh_invalid" -> "Invalid refresh token"
    else -> code
}

private const val BEARER_PREFIX = "Bearer "

/** Optional device label on auth endpoints: registers the device and links the new session (P1-3). */
const val DEVICE_LABEL_HEADER = "X-Device-Label"

/**
 * Bearer guard: verifies the access JWT and resolves the user, then hands the call
 * to [handler]. Invalid/missing/expired tokens and banned accounts get a uniform
 * documented 401 Error body (B-04).
 */
internal suspend fun ApplicationCall.withUser(service: AuthService, handler: suspend (User) -> Unit) {
    val header = request.header(io.ktor.http.HttpHeaders.Authorization) ?: ""
    val token = header.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)?.trim()
    val user = token?.let { service.currentUser(it) }
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, err("unauthorized", "Missing or invalid access token"))
    } else {
        handler(user)
    }
}

/**
 * P1-2 auth routes. All error bodies use the documented Error schema; every
 * state change is appended to the hash-chained event store via AuthService.
 */
fun Route.authRoutes(service: AuthService) {
    route("/v1/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            when (val r = service.register(req.displayName, req.password, req.phone, req.email, req.locale, call.request.headers[DEVICE_LABEL_HEADER])) {
                is AuthService.RegisterResult.Success ->
                    call.respond(HttpStatusCode.Created, AuthSuccessDto(r.user.toDto(), r.pair.toDto()))
                is AuthService.RegisterResult.Duplicate ->
                    call.respond(HttpStatusCode.Conflict, err("identifier_taken", "Phone or email already registered"))
                is AuthService.RegisterResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err(r.code, "Validation failed: ${r.field}"))
            }
        }
        post("/login/password") {
            val req = call.receive<PasswordLoginRequest>()
            when (val r = service.loginWithPassword(req.identifier, req.password, call.request.headers[DEVICE_LABEL_HEADER])) {
                is AuthService.PasswordLoginResult.Success ->
                    call.respond(HttpStatusCode.OK, AuthSuccessDto(r.user.toDto(), r.pair.toDto()))
                AuthService.PasswordLoginResult.InvalidCredentials ->
                    call.respond(HttpStatusCode.Unauthorized, err("invalid_credentials", "Unknown identifier or wrong password"))
                AuthService.PasswordLoginResult.Disabled ->
                    call.respond(HttpStatusCode.Forbidden, err("login_method_disabled", "Password login is disabled"))
                AuthService.PasswordLoginResult.Banned ->
                    call.respond(HttpStatusCode.Forbidden, err("account_banned", "Account is banned"))
            }
        }
        post("/otp/request") {
            val req = call.receive<OtpIssueRequest>()
            val channel = OtpChannel.entries.firstOrNull { it.name.equals(req.channel, ignoreCase = true) }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    err("unknown_channel", "channel must be one of sms|telegram|email|whatsapp"),
                )
            when (val r = service.requestOtp(channel, req.target.trim(), req.locale ?: "en")) {
                is AuthService.OtpIssueResult.Issued ->
                    call.respond(HttpStatusCode.OK, OtpIssuedDto(r.requestId, r.expiresInSeconds, r.resendAfterSeconds))
                is AuthService.OtpIssueResult.Refused -> when (r.code) {
                    "channel_disabled" ->
                        call.respond(HttpStatusCode.Forbidden, err("channel_disabled", "OTP channel disabled by admin"))
                    "rate_limited" ->
                        call.respond(HttpStatusCode.TooManyRequests, err("rate_limited", "Too many OTP requests; try later"))
                    "resend_too_soon" ->
                        call.respond(HttpStatusCode.TooManyRequests, err("resend_too_soon", "Please wait before requesting again"))
                    else ->
                        call.respond(HttpStatusCode.BadGateway, err("otp_delivery_failed", "Provider error: ${r.code}"))
                }
            }
        }
        post("/otp/verify") {
            val req = call.receive<OtpVerifyRequest>()
            when (val r = service.verifyOtp(req.requestId, req.code, req.displayName, req.locale ?: "en", call.request.headers[DEVICE_LABEL_HEADER])) {
                is AuthService.OtpVerifyResult.Success ->
                    call.respond(HttpStatusCode.OK, AuthSuccessDto(r.user.toDto(), r.pair.toDto(), created = r.created))
                is AuthService.OtpVerifyResult.Failed ->
                    call.respond(HttpStatusCode.Unauthorized, err(r.code, mapFailureMessage(r.code)))
            }
        }
        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            when (val r = service.refresh(req.refreshToken)) {
                is AuthService.RefreshResult2.Success ->
                    call.respond(HttpStatusCode.OK, AuthRefreshedDto(tokens = r.pair.toDto()))
                is AuthService.RefreshResult2.Failed ->
                    call.respond(HttpStatusCode.Unauthorized, err(r.code, mapFailureMessage(r.code)))
            }
        }
        post("/logout") {
            val req = call.receive<LogoutRequest>()
            service.logout(req.refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/v1/me") {
        call.withUser(service) { user -> call.respond(HttpStatusCode.OK, user.toDto()) }
    }
}

@Serializable
private data class AuthRefreshedDto(val tokens: TokenPairDto)

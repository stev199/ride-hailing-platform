package com.ridehailing.backend

import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.DevInboxOtpProvider
import com.ridehailing.backend.auth.EmailOtpProvider
import com.ridehailing.backend.auth.HttpRemoteTransport
import com.ridehailing.backend.auth.InMemoryUserStore
import com.ridehailing.backend.auth.JwtService
import com.ridehailing.backend.auth.LoginMethodsConfig
import com.ridehailing.backend.auth.OtpChannel
import com.ridehailing.backend.auth.OtpService
import com.ridehailing.backend.auth.PasswordService
import com.ridehailing.backend.auth.SmsGatewayOtpProvider
import com.ridehailing.backend.auth.TelegramBotOtpProvider
import com.ridehailing.backend.auth.authRoutes
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.health.healthRoutes
import com.ridehailing.backend.testprofile.testRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

const val BACKEND_VERSION = "0.1.0"

/** Registered method map used to compute RFC 9110 Allow headers on 405s (ST204 check). */
private val ROUTE_METHODS: Map<String, String> = mapOf(
    "/health" to "GET",
    "/test/barrier" to "GET",
    "/test/seed" to "POST",
    "/test/otp/latest" to "GET",
    "/v1/auth/register" to "POST",
    "/v1/auth/login/password" to "POST",
    "/v1/auth/otp/request" to "POST",
    "/v1/auth/otp/verify" to "POST",
    "/v1/auth/refresh" to "POST",
    "/v1/auth/logout" to "POST",
    "/v1/me" to "GET",
)

private fun allowFor(path: String): String = when {
    ROUTE_METHODS.containsKey(path) -> ROUTE_METHODS[path]!!
    path.startsWith("/test/barrier") -> "GET"
    path.startsWith("/test/seed") -> "POST"
    path.startsWith("/test/otp/") -> "GET"
    else -> "GET, POST"
}

/** Runtime dependency graph for the auth module (P1-2). */
class AuthGraph(
    val users: InMemoryUserStore = InMemoryUserStore(),
    val passwords: PasswordService = PasswordService(),
    val tokens: JwtService = JwtService(),
    val devInbox: DevInboxOtpProvider = DevInboxOtpProvider(),
    val loginMethods: LoginMethodsConfig = LoginMethodsConfig(),
) {
    val otp = OtpService(providers = buildProviders(devInbox), loginMethods = loginMethods)

    val eventChain = EventChain()

    val service = AuthService(
        users = users,
        passwords = passwords,
        tokens = tokens,
        otp = otp,
        loginMethods = loginMethods,
        eventRecorder = { type, payload -> eventChain.append(type, payload) },
    )

    private fun buildProviders(devInbox: DevInboxOtpProvider): Map<OtpChannel, com.ridehailing.backend.auth.OtpProvider> =
        buildMap {
            put(OtpChannel.SMS, devInbox) // dev inbox is the default SMS-transport adapter in dev/test
            System.getenv("SMS_GATEWAY_URL")?.let { url ->
                put(OtpChannel.SMS, SmsGatewayOtpProvider(url, System.getenv("SMS_GATEWAY_TOKEN"), System.getenv("SMS_GATEWAY_SENDER"), HttpRemoteTransport()))
            }
            System.getenv("TELEGRAM_BOT_TOKEN")?.let { token ->
                put(OtpChannel.TELEGRAM, TelegramBotOtpProvider(token, HttpRemoteTransport()))
            }
            System.getenv("SMTP_HOST")?.let { host ->
                put(
                    OtpChannel.EMAIL,
                    EmailOtpProvider(
                        host = host,
                        port = System.getenv("SMTP_PORT")?.toIntOrNull() ?: 587,
                        username = System.getenv("SMTP_USERNAME") ?: "",
                        password = System.getenv("SMTP_PASSWORD") ?: "",
                        fromAddress = System.getenv("SMTP_FROM") ?: "",
                    ),
                )
            }
        }
}

/**
 * Application module. The test profile enables the /test/ routes (barrier + seed +
 * dev-inbox OTP retrieval) used by cross-actor E2E (D5). A test proves these routes
 * are absent when the profile is off (release builds).
 */
fun Application.module(
    testProfile: Boolean = System.getenv("TEST_PROFILE") == "true",
    auth: AuthGraph = AuthGraph(),
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorDto(code = "not_found", message = "Resource not found"),
            )
        }
        // RFC 9110: 405 must carry Allow listing supported methods (contract-checked by schemathesis ST204).
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            val allow = allowFor(call.request.path())
            call.response.headers.append(HttpHeaders.Allow, allow)
            call.respond(HttpStatusCode.MethodNotAllowed, ErrorDto(code = "method_not_allowed", message = "Allowed: $allow"))
        }
        // Malformed/unreadable request bodies are client errors (schemathesis ST101/ST103):
        // Ktor wraps body-conversion failures in BadRequestException; answer 400, not 500.
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto(code = "invalid_body", message = "Malformed or unreadable request body"),
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(code = "internal_error", message = "Internal error"),
            )
        }
    }
    val eventChain = auth.eventChain
    val configBundle = SignedConfigBundle.inMemoryDevKeys()
    routing {
        healthRoutes(version = BACKEND_VERSION)
        authRoutes(auth.service)
        if (testProfile) testRoutes(eventChain, configBundle, auth.devInbox)
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

@Serializable
data class ErrorDto(val code: String, val message: String, val traceId: String? = null)

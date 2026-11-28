package com.ridehailing.backend

import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.DevInboxOtpProvider
import com.ridehailing.backend.auth.EmailOtpProvider
import com.ridehailing.backend.auth.HttpRemoteTransport
import com.ridehailing.backend.auth.InMemoryDeviceStore
import com.ridehailing.backend.auth.InMemoryUserStore
import com.ridehailing.backend.auth.JwtService
import com.ridehailing.backend.auth.LoginMethodsConfig
import com.ridehailing.backend.auth.OtpChannel
import com.ridehailing.backend.auth.OtpService
import com.ridehailing.backend.auth.PasswordService
import com.ridehailing.backend.auth.SmsGatewayOtpProvider
import com.ridehailing.backend.auth.TelegramBotOtpProvider
import com.ridehailing.backend.auth.UserStore
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.DeviceStore
import com.ridehailing.backend.auth.authRoutes
import com.ridehailing.backend.config.ConfigService
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.config.configRoutes
import com.ridehailing.backend.config.promoConfigRoutes
import com.ridehailing.backend.fares.FareService
import com.ridehailing.backend.fares.OsrmClient
import com.ridehailing.backend.fares.fareRoutes
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.health.healthRoutes
import com.ridehailing.backend.rides.RideService
import com.ridehailing.backend.rides.InMemoryRideStore
import com.ridehailing.backend.rides.rideRoutes
import com.ridehailing.backend.rides.driverRoutes
import com.ridehailing.backend.rides.adminLiveRoutes
import com.ridehailing.backend.rides.adminTripRoutes
import com.ridehailing.backend.verification.verificationRoutes
import com.ridehailing.backend.wallet.WalletService
import com.ridehailing.backend.wallet.walletRoutes
import com.ridehailing.backend.wallet.walletConfigRoutes
import com.ridehailing.backend.rbac.AdminService
import com.ridehailing.backend.rbac.adminRoutes
import com.ridehailing.backend.testprofile.testRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
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
    "/v1/me/sessions" to "GET",
    "/v1/me/sessions/{familyId}" to "DELETE",
    "/v1/config" to "GET",
    "/v1/admin/config/login-methods" to "GET, PATCH",
    "/v1/admin/config/fares" to "GET, PATCH",
    "/v1/admin/config/wallet" to "GET, PATCH",
    "/v1/admin/config/promos" to "GET, PATCH",
    "/v1/admin/rides" to "GET",
    "/v1/admin/rides/{id}" to "GET",
    "/v1/admin/rides/{id}/cancel" to "POST",
    "/v1/admin/rides/{id}/refund" to "POST",
    "/v1/admin/live" to "GET",
    "/v1/wallet" to "GET",
    "/v1/wallet/transactions" to "GET",
    "/v1/wallet/transfer" to "POST",
    "/v1/admin/users/{id}/wallet-credit" to "POST",
    // P6-1 verification center (round-23: the fuzzer hit these - ST204 TRACE 404s +
    // ST205 wrong Allow from the generic else-fallthrough; every path needs its
    // exact method list here).
    "/v1/verification" to "POST",
    "/v1/verification/me" to "GET",
    "/v1/admin/verification" to "GET",
    "/v1/admin/verification/{id}" to "GET",
    "/v1/admin/verification/{id}/approve" to "POST",
    "/v1/admin/verification/{id}/reject" to "POST",
    "/v1/rides/estimate" to "POST",
    "/v1/rides" to "GET, POST",
    "/v1/rides/{id}" to "GET",
    "/v1/rides/{id}/cancel" to "POST",
    // P5 driver flow (round-15: the missing entries made the catch-all answer
    // Allow: GET for the POST-only action paths - schemathesis ST205/ST204).
    "/v1/driver/duty" to "POST",
    "/v1/driver/rides/available" to "GET",
    "/v1/driver/rides" to "GET",
    "/v1/rides/{id}/accept" to "POST",
    "/v1/rides/{id}/arrive" to "POST",
    "/v1/rides/{id}/start" to "POST",
    "/v1/rides/{id}/complete" to "POST",
    "/v1/admin/users" to "GET, POST",
    "/v1/admin/users/{id}" to "GET, PATCH",
    "/v1/admin/users/{id}/reset-password" to "POST",
    "/v1/admin/users/{id}/force-logout" to "POST",
    "/v1/admin/audit" to "GET",
)

private fun allowFor(path: String): String = when {
    ROUTE_METHODS.containsKey(path) -> ROUTE_METHODS[path]!!
    path.startsWith("/test/barrier") -> "GET"
    path.startsWith("/test/seed") -> "POST"
    path.startsWith("/test/otp/") -> "GET"
    path.startsWith("/v1/me/sessions/") -> "DELETE"
    path.startsWith("/v1/admin/users/") && path.endsWith("/reset-password") -> "POST"
    path.startsWith("/v1/admin/users/") && path.endsWith("/force-logout") -> "POST"
    path.startsWith("/v1/admin/users/") && path.endsWith("/wallet-credit") -> "POST"
    path.startsWith("/v1/admin/users/") -> "GET, PATCH"
    path.startsWith("/v1/wallet/transactions") -> "GET"
    path == "/v1/wallet" -> "GET"
    path == "/v1/wallet/transfer" -> "POST"
    path.startsWith("/v1/admin/config/fares") -> "GET, PATCH"
    path.startsWith("/v1/admin/config/wallet") -> "GET, PATCH"
    path.startsWith("/v1/admin/config/promos") -> "GET, PATCH"
    path.startsWith("/v1/admin/rides/") && path.endsWith("/cancel") -> "POST"
    path.startsWith("/v1/admin/rides/") && path.endsWith("/refund") -> "POST"
    path.startsWith("/v1/admin/rides") -> "GET"
    path.startsWith("/v1/rides/") && path.endsWith("/cancel") -> "POST"
    // P5 driver action paths (must precede the generic /v1/rides/ rule).
    path.startsWith("/v1/rides/") && path.endsWith("/accept") -> "POST"
    path.startsWith("/v1/rides/") && path.endsWith("/arrive") -> "POST"
    path.startsWith("/v1/rides/") && path.endsWith("/start") -> "POST"
    path.startsWith("/v1/rides/") && path.endsWith("/complete") -> "POST"
    path.startsWith("/v1/driver/duty") -> "POST"
    path.startsWith("/v1/driver/rides/available") -> "GET"
    path.startsWith("/v1/driver/rides") -> "GET"
    path.startsWith("/v1/rides/") -> "GET"
    path == "/v1/rides" -> "GET, POST"
    path.startsWith("/v1/admin/verification/") && path.endsWith("/approve") -> "POST"
    path.startsWith("/v1/admin/verification/") && path.endsWith("/reject") -> "POST"
    path.startsWith("/v1/admin/verification/") -> "GET"
    path.startsWith("/v1/admin/verification") -> "GET"
    // Exact resources (ST205 wants exactly the documented methods per resource).
    path == "/v1/verification" -> "POST"
    path == "/v1/verification/me" -> "GET"
    path.startsWith("/v1/admin/audit") -> "GET"
    else -> "GET, POST"
}

/**
 * True when the path template is REGISTERED in the API (exact map hit or one of the
 * parameterized prefixes). Used by the catch-all route to separate 405 (known path,
 * undeclared method - schemathesis ST204) from 404 (unknown path).
 */
private fun isKnownRoutePath(path: String): Boolean = when {
    // /test/* first: NOT known even though ROUTE_METHODS lists them - in release builds
    // those routes do not exist and the absence proof (HealthAndRoutesTest/AuthRoutesTest)
    // must keep answering 404.
    path.startsWith("/test/") -> false
    ROUTE_METHODS.containsKey(path) -> true
    path.startsWith("/health") -> true
    path.startsWith("/v1/auth/") -> true
    path.startsWith("/v1/me/sessions/") -> true
    path.startsWith("/v1/admin/") -> true
    path.startsWith("/v1/verification") -> true
    path.startsWith("/v1/rides/") -> true
    path.startsWith("/v1/driver/") -> true
    else -> false
}

/** Runtime dependency graph for the auth module (P1-2) + admin/RBAC (P1-3). */
class AuthGraph(
    val users: UserStore = InMemoryUserStore(),
    val passwords: PasswordService = PasswordService(),
    val tokens: JwtService = JwtService(),
    val devInbox: DevInboxOtpProvider = DevInboxOtpProvider(),
    val devices: DeviceStore = InMemoryDeviceStore(),
    val eventChain: EventChain = EventChain(),
    // P4-2: rides + ledger (in-memory default, PG in defaultGraph - CI double-verifies).
    val rideStore: com.ridehailing.backend.rides.RideStore = com.ridehailing.backend.rides.InMemoryRideStore(),
    val verificationStore: com.ridehailing.backend.verification.VerificationStore =
        com.ridehailing.backend.verification.InMemoryVerificationStore(),
    val ledger: com.ridehailing.backend.ledger.LedgerStore = com.ridehailing.backend.ledger.InMemoryLedgerStore(),
) {
    private val recorder = AuthService.EventChainRecorder { type, payload -> eventChain.append(type, payload) }

    /** P7-1 wallet service (rides on the same ledger the CI PG job double-verifies). */
    val walletService: WalletService by lazy {
        WalletService(
            ledger = ledger,
            users = object : WalletService.UserDirectory {
                override fun byPhone(phone: String) = users.findByPhone(phone)
                override fun byId(id: String) = users.findById(id)
            },
            config = { config.walletConfig },
        )
    }

    /** P1-5a: the live config center - owns LoginMethodsConfig + signed bundle history. */
    val config: ConfigService = ConfigService(
        bundle = SignedConfigBundle.inMemoryDevKeys(),
        eventRecorder = { type, payload -> eventChain.append(type, payload) },
    )

    val otp = OtpService(providers = buildProviders(devInbox), loginMethods = { config.loginMethods })

    val service = AuthService(
        users = users,
        passwords = passwords,
        tokens = tokens,
        otp = otp,
        loginMethods = { config.loginMethods },
        eventRecorder = recorder,
        devices = devices,
    )

    val admin = AdminService(
        users = users,
        passwords = passwords,
        tokens = tokens,
        eventRecorder = recorder,
    )

    /** P4-1: fare engine - OSRM for live routes, straight-line fallback when unreachable. */
    val osrm: OsrmClient? = System.getenv("OSRM_URL")?.let { OsrmClient(it) }
        // Default: the compose osrm service port (geo profile); null = straight-line only.
        ?: OsrmClient("http://localhost:5000")

    val fareService = FareService(fares = { config.fares }, osrm = osrm)

    val rideService = RideService(
        store = rideStore,
        fares = { config.fares },
        bundle = config.quoteSigningBundle,
        ledger = ledger,
        walletProvider = { walletService },
        walletConfigProvider = { config.walletConfig },
        promoConfigProvider = { config.promos },
        osrm = osrm,
        eventRecorder = { type, payload -> eventChain.append(type, payload) },
    )
    val verification = com.ridehailing.backend.verification.VerificationService(
        store = verificationStore,
        eventRecorder = { type, payload -> eventChain.append(type, payload) },
    )

    /**
     * Bootstrap admin (P1-3): when AUTH_BOOTSTRAP_ADMIN_PHONE + AUTH_BOOTSTRAP_ADMIN_PASSWORD
     * are set and NO admin exists, the first ADMIN_SUPER is created at startup (audited
     * with actorId "system"). Subsequent admins are created BY admins (AdminService guards
     * ADMIN_* role assignment to ADMIN_SUPER). No-op without the env pair.
     */
    fun bootstrapAdminFromEnv() {
        val phone = System.getenv("AUTH_BOOTSTRAP_ADMIN_PHONE") ?: return
        val password = System.getenv("AUTH_BOOTSTRAP_ADMIN_PASSWORD") ?: return
        val anyAdmin = users.search(null, 100, 0).any { it.role.isAdmin }
        if (anyAdmin) return
        val system = User(
            id = "system", phone = null, email = null, passwordHash = null,
            displayName = "system", role = Role.ADMIN_SUPER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        when (val r = admin.createUser(system, "Bootstrap Admin", phone, null, Role.ADMIN_SUPER, password, null)) {
            is AdminService.CreateResult.Created ->
                eventChain.append("system.admin_bootstrap", mapOf("userId" to r.user.id, "actorId" to "system"))
            else -> throw IllegalStateException("admin bootstrap failed: $r")
        }
    }

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
 * Default graph: in-memory unless POSTGRES_URL is set, in which case Flyway migrates
 * (V1+V2) and the PG-backed stores take over (P1-3e). Same ports either way - the
 * route layer and tests are storage-agnostic.
 */
fun defaultGraph(): AuthGraph {
    val url = System.getenv("POSTGRES_URL") ?: return AuthGraph()
    val user = System.getenv("POSTGRES_USER") ?: "ridehailing"
    val password = System.getenv("POSTGRES_PASSWORD") ?: "test"
    val connect = { java.sql.DriverManager.getConnection(url, user, password) }
    // PgEventStore.open runs Flyway (V1+V2) and reloads the persisted chain.
    val events = com.ridehailing.backend.db.PgEventStore.open(url, user, password)
    return AuthGraph(
        users = com.ridehailing.backend.db.PgUserStore(connect),
        devices = com.ridehailing.backend.db.PgDeviceStore(connect),
        eventChain = events,
        ledger = com.ridehailing.backend.db.PgLedgerStore(connect),
        rideStore = com.ridehailing.backend.db.PgRideStore(connect),
        verificationStore = com.ridehailing.backend.db.PgVerificationStore(connect),
    )
}

/**
 * Application module. The test profile enables the /test/ routes (barrier + seed +
 * dev-inbox OTP retrieval) used by cross-actor E2E (D5). A test proves these routes
 * are absent when the profile is off (release builds).
 */
fun Application.module(
    testProfile: Boolean = System.getenv("TEST_PROFILE") == "true",
    auth: AuthGraph = defaultGraph(),
) {
    install(ContentNegotiation) {
        // Forward-compatible parsing (D46): unknown keys are ignored (schema-valid requests
        // must not 400 on additionalProperties), defaults are ALWAYS encoded so required
        // fields like AuthSuccess.created never vanish from responses.
        json(
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
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
        // Missing/unsupported Content-Type on POSTs -> 415 with the documented Error body
        // (schemathesis ST103/ST108; Ktor's default 415 carries no JSON body).
        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorDto(code = "unsupported_media_type", message = "Content-Type must be application/json"),
            )
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
    auth.bootstrapAdminFromEnv()
    routing {
        healthRoutes(version = BACKEND_VERSION)
        authRoutes(auth.service)
        adminRoutes(auth.service, auth.admin, auth.eventChain)
        configRoutes(auth.config, auth.service)
        fareRoutes(auth.service, auth.fareService, auth.config)
        rideRoutes(auth.service, auth.rideService, auth.config.publicKeyHex())
        driverRoutes(auth.service, auth.rideService)
        adminTripRoutes(auth.service, auth.rideStore, auth.eventChain, auth.rideService, auth.walletService)
        adminLiveRoutes(auth.service, auth.rideService, auth.rideStore)
        verificationRoutes(auth.service, auth.verification)
        walletRoutes(auth.service, auth.walletService)
        walletConfigRoutes(auth.service, auth.config)
        promoConfigRoutes(auth.service, auth.config)
        if (testProfile) testRoutes(eventChain, configBundle, auth.devInbox)
        // Catch-all (registered LAST - earlier routes win at equal resolution quality):
        // Ktor answers 404 for a KNOWN path hit with an undeclared method, but the
        // contract expects 405 + Allow (RFC 9110, schemathesis ST204). Known path ->
        // 405 with the computed Allow header; unknown path -> the standard 404 body.
        route("{...}") {
            handle {
                val path = call.request.path()
                if (isKnownRoutePath(path)) {
                    val allow = allowFor(path)
                    // NO Allow append here - the status(HttpStatusCode.MethodNotAllowed)
                    // hook below is the SINGLE header source (round-23: the double append
                    // produced "GET, POST, GET, POST" -> schemathesis ST205).
                    call.respond(
                        HttpStatusCode.MethodNotAllowed,
                        ErrorDto(code = "method_not_allowed", message = "Allowed: $allow"),
                    )
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(code = "not_found", message = "Resource not found"),
                    )
                }
            }
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

@Serializable
data class ErrorDto(val code: String, val message: String, val traceId: String? = null)

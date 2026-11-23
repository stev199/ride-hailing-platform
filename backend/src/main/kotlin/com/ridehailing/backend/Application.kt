package com.ridehailing.backend

import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.health.healthRoutes
import com.ridehailing.backend.testprofile.testRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

const val BACKEND_VERSION = "0.1.0"

/**
 * Application module. The test profile enables the /test/ routes (barrier + seed)
 * used by cross-actor E2E (D5). A test proves these routes are absent when the
 * profile is off (release builds).
 */
fun Application.module(testProfile: Boolean = System.getenv("TEST_PROFILE") == "true") {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorDto(code = "not_found", message = "Resource not found"),
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(code = "internal_error", message = "Internal error"),
            )
        }
    }
    val eventChain = EventChain()
    val configBundle = SignedConfigBundle.inMemoryDevKeys()
    routing {
        healthRoutes(version = BACKEND_VERSION)
        if (testProfile) testRoutes(eventChain, configBundle)
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

@Serializable
data class ErrorDto(val code: String, val message: String, val traceId: String? = null)

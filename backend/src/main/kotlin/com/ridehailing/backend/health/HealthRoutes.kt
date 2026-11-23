package com.ridehailing.backend.health

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(val status: String, val version: String)

/** GET /health - liveness probe per OpenAPI (compose healthcheck + CI wait scripts). */
fun Route.healthRoutes(version: String) {
    get("/health") {
        call.respond(HealthDto(status = "ok", version = version))
    }
}

package com.ridehailing.backend.testprofile

import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.events.EventChain
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Test-profile-only routes (D11.4). Registered exclusively when TEST_PROFILE=true.
 * A unit test proves they are absent (404) when the profile is off, i.e. release
 * builds expose no test surface.
 */
fun Route.testRoutes(eventChain: EventChain, configBundle: SignedConfigBundle) {
    route("/test") {
        get("/barrier/{name}") {
            // Cross-actor orchestration barrier (D5): actors poll until all participants
            // have arrived; v0.1 records arrivals in the event chain for auditability.
            val name = call.parameters["name"] ?: ""
            eventChain.append("test.barrier", mapOf("name" to name))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/seed") {
            val req = call.receive<SeedRequest>()
            eventChain.append("test.seed", mapOf("namespace" to req.namespace))
            configBundle.sign(
                configCanonicalJson = EventChain.canonicalJson(mapOf("namespace" to req.namespace)),
                version = 1L,
                issuedAtSec = System.currentTimeMillis() / 1000,
            )
            call.respond(HttpStatusCode.OK, SeedResponse(ok = true, namespace = req.namespace))
        }
    }
}

@Serializable
data class SeedRequest(val namespace: String)

@Serializable
data class SeedResponse(val ok: Boolean, val namespace: String)

package com.ridehailing.backend.config

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * P8-1 cancellation config routes (fares/wallet/promos pattern): GET (CONFIG_READ)
 * + PATCH (CONFIG_WRITE, D75 FULL-REPLACE - the admin sends the edited document,
 * the server validates it completely; invalid configs never touch live state and
 * never burn a version). Every accepted patch bumps the signed bundle version and
 * appends a `config.cancellation.updated` audit event.
 *
 * Contract lessons carried over verbatim from the promos section (D49/D75 lineage):
 * - presence-strict decode: the schema-required keys (freeCancelWindowSec,
 *   riderReasons, driverReasons) must be PRESENT and typed - kotlinx defaults never
 *   absorb a missing key (the round-11 fares partial-reset class); a PRESENT
 *   integral field that fails the integral decode (1.5) is schema-invalid and
 *   rejects, never defaults (round-31 ST202 parity).
 * - D49 integral-float leniency: JSON Schema treats 1.0 as a valid integer, so
 *   0.0 decodes as 0 and 0.5 rejects.
 * - additionalProperties: false on the section (documented = enforced): unknown
 *   keys are schema violations -> 400 invalid_body.
 * - The per-reason 2..120-char and 20-item bounds ride
 *   CancellationConfig.validationErrors -> 400 invalid_cancellation (the single
 *   write path keeps the store in-bounds, round-33 ST108/ST201 lesson: GET can
 *   never echo an out-of-bounds value).
 * - NO uniqueness rule on the reason lists: JSON Schema cannot express per-entry
 *   uniqueness and duplicates are dead config, not invalid config (round-31/36c
 *   lineage).
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/** D49 integral-float leniency (promos pattern): 1.0 -> 1, 1.5 -> null. */
private fun JsonObject.integralLong(name: String): Long? {
    val v = this[name] as? JsonPrimitive ?: return null
    if (v.isString) return null
    return v.longOrNull ?: v.doubleOrNull
        ?.takeIf { it == kotlin.math.floor(it) && it.isFinite() && kotlin.math.abs(it) <= 9.0e15 }
        ?.toLong()
}

private val KNOWN_SECTION_KEYS = setOf("freeCancelWindowSec", "riderReasons", "driverReasons")

private fun JsonObject.decodedReasons(name: String): List<String>? {
    val arr = this[name] as? JsonArray ?: return null
    val out = ArrayList<String>(arr.size)
    for (entry in arr) {
        val s = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        out.add(s)
    }
    return out
}

@Serializable
data class CancellationConfigUpdatedDto(
    val version: Long,
    val config: CancellationConfig,
)

fun Route.cancellationConfigRoutes(service: AuthService, config: ConfigService) {
    route("/v1/admin/config/cancellation") {
        get {
            call.withPermission(service, Perm.CONFIG_READ) { _ ->
                call.respond(HttpStatusCode.OK, config.cancellationConfig)
            }
        }
        patch {
            call.withPermission(service, Perm.CONFIG_WRITE) { user ->
                val obj = call.receive<JsonObject>()
                // additionalProperties: false - unknown keys are schema violations -> reject.
                if (obj.keys.any { it !in KNOWN_SECTION_KEYS }) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_body", "unknown key in the cancellation section"))
                    return@withPermission
                }
                // Presence-strict required keys (promos codes pattern).
                val window = obj.integralLong("freeCancelWindowSec")
                    ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "freeCancelWindowSec is required and must be an integer"),
                    )
                val riderReasons = obj.decodedReasons("riderReasons")
                    ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "riderReasons is required and must be an array of strings"),
                    )
                val driverReasons = obj.decodedReasons("driverReasons")
                    ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "driverReasons is required and must be an array of strings"),
                    )
                when (val res = config.updateCancellation(user.id, CancellationConfig(window, riderReasons, driverReasons))) {
                    is ConfigService.UpdateResult.Updated ->
                        call.respond(
                            HttpStatusCode.OK,
                            CancellationConfigUpdatedDto(version = res.entry.version, config = config.cancellationConfig),
                        )
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_cancellation", res.code))
                }
            }
        }
    }
}

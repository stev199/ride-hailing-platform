package com.ridehailing.backend.fares

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.withUser
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * P4-1 fare routes:
 * - POST /v1/rides/estimate - ANY authenticated user; computes ride-type estimate
 *   ranges from the live fares + OSRM (degrades to the calibrated straight-line
 *   fallback with routeSource=straight_line when the geo stack is unreachable).
 *   Read-only computation (no rider-specific data), documented for all roles.
 * - GET /v1/admin/config/fares (CONFIG_READ) - the current fares section.
 * - PATCH /v1/admin/config/fares (CONFIG_WRITE) - D75 full-replace edit; an
 *   accepted patch bumps the signed bundle version and is audited.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class FareEstimateRequest(
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val city: String,
    val rideType: String? = null,
)

@Serializable
data class FareBreakdownDto(
    val base: Double,
    val distanceCost: Double,
    val timeCost: Double,
    val surgeMultiplier: Double,
    val surgeReason: String?,
    val minimumApplied: Boolean,
    val bookingFee: Double,
    val total: Double,
)

@Serializable
data class RideTypeEstimateDto(
    val rideType: String,
    val currency: String,
    val lowTotal: Double,
    val highTotal: Double,
    val surgeMultiplier: Double,
    val surgeReason: String?,
    val breakdown: FareBreakdownDto,
)

@Serializable
data class FareEstimateResponse(
    val city: String,
    val routeSource: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val estimates: List<RideTypeEstimateDto>,
    /** Permanent rider-facing disclaimer key - the client renders localized text (spec §pricing). */
    val disclaimer: String = "estimated_price_not_final",
)

private fun FareService.RideTypeEstimate.toDto() = RideTypeEstimateDto(
    rideType = rideType,
    currency = currency,
    lowTotal = lowTotal,
    highTotal = highTotal,
    surgeMultiplier = surgeMultiplier,
    surgeReason = surgeReason,
    breakdown = FareBreakdownDto(
        base = breakdown.base,
        distanceCost = breakdown.distanceCost,
        timeCost = breakdown.timeCost,
        surgeMultiplier = breakdown.surgeMultiplier,
        surgeReason = breakdown.surgeReason,
        minimumApplied = breakdown.minimumApplied,
        bookingFee = breakdown.bookingFee,
        total = breakdown.total,
    ),
)

fun Route.fareRoutes(service: AuthService, fareService: FareService, config: com.ridehailing.backend.config.ConfigService) {
    // GET /v1/rides/estimate is NOT a declared method (estimate is POST-only). Without
    // this explicit handler the parameterized GET /v1/rides/{id} route shadows the path
    // ("estimate" parsed as an id) and answers 401 before the ST204 405 catch-all can
    // fire (round-11). Registered BEFORE the rides routes so it wins resolution order.
    get("/v1/rides/estimate") {
        call.response.headers.append(HttpHeaders.Allow, "POST")
        call.respond(HttpStatusCode.MethodNotAllowed, err("method_not_allowed", "Allowed: POST"))
    }
    post("/v1/rides/estimate") {
        call.withUser(service) { _ ->
            val req = call.receive<FareEstimateRequest>()
            // Coordinate sanity before math: NaN/inf/hemisphere-buster inputs are client errors.
            val coordsOk = listOf(req.pickupLat, req.pickupLon, req.dropoffLat, req.dropoffLon)
                .all { it.isFinite() && kotlin.math.abs(it) <= 180.0 }
            if (!coordsOk) {
                call.respond(HttpStatusCode.BadRequest, err("invalid_coordinates", "Coordinates must be finite and within +/-180"))
                return@withUser
            }
            when (val r = fareService.estimate(req.pickupLat, req.pickupLon, req.dropoffLat, req.dropoffLon, req.city, req.rideType)) {
                is FareService.EstimateOutcome.Ok ->
                    call.respond(
                        HttpStatusCode.OK,
                        FareEstimateResponse(
                            city = r.result.city,
                            routeSource = r.result.routeSource,
                            distanceMeters = r.result.distanceMeters,
                            durationSeconds = r.result.durationSeconds,
                            estimates = r.result.estimates.map { it.toDto() },
                        ),
                    )
                is FareService.EstimateOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err(r.code, "Invalid estimate request: ${r.field}"))
            }
        }
    }
    get("/v1/admin/config/fares") {
        call.withPermission(service, Perm.CONFIG_READ) { _ ->
            call.respond(HttpStatusCode.OK, config.fares)
        }
    }
    patch("/v1/admin/config/fares") {
        call.withPermission(service, Perm.CONFIG_WRITE) { user ->
            // D75 full-replace: the PATCH body must carry the WHOLE fares document.
            // kotlinx.serialization treats default-valued fields as OPTIONAL, so a
            // partial body used to decode successfully and silently RESET straightLine/
            // quote to their defaults (round-11 ST201 caught exactly that: a body with
            // only `cities` was accepted 200). Decode to a raw object first and demand
            // every schema-required key at each present level; missing -> 400, never a
            // silent default reset.
            val raw = call.receive<kotlinx.serialization.json.JsonObject>()
            val missing = missingRequiredFareKeys(raw)
            if (missing.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err("invalid_fares", "Missing required property: ${missing.first()}"),
                )
                return@withPermission
            }
            // Same decode policy as the app module (D46): unknown keys ignored; a body
            // that does not FIT the document (wrong element types) is a client 400.
            val req = try {
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                }.decodeFromJsonElement(FareConfig.serializer(), raw)
            } catch (_: kotlinx.serialization.SerializationException) {
                call.respond(HttpStatusCode.BadRequest, err("invalid_fares", "Body does not match the fares document schema"))
                return@withPermission
            }
            when (val r = config.updateFares(user.id, req)) {
                is com.ridehailing.backend.config.ConfigService.UpdateResult.Updated ->
                    call.respond(HttpStatusCode.OK, config.fares)
                is com.ridehailing.backend.config.ConfigService.UpdateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err("invalid_fares", r.code))
            }
        }
    }
}

/**
 * Schema-required keys (docs/api/openapi.yaml components) that must be present at
 * every PRESENT level of the full-replace PATCH body. Optional properties (bookingFee,
 * cancelFee, waitFeePerMinute, night, peak) stay optional; when an optional OBJECT is
 * supplied its own required keys are enforced (full-replace never fills defaults in).
 */
private fun missingRequiredFareKeys(body: kotlinx.serialization.json.JsonObject): List<String> {
    val out = mutableListOf<String>()
    fun obj(element: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonObject? =
        element as? kotlinx.serialization.json.JsonObject

    for (k in listOf("cities", "straightLine", "quote")) if (k !in body) out += k
    obj(body["straightLine"])?.let { sl ->
        for (k in listOf("detourFactor", "assumedAvgSpeedKmh")) if (k !in sl) out += "straightLine.$k"
    }
    obj(body["quote"])?.let { q ->
        for (k in listOf("validitySeconds", "mode", "meteredCapMultiplier")) if (k !in q) out += "quote.$k"
    }
    obj(body["cities"])?.forEach { (city, cityEl) ->
        val c = obj(cityEl) ?: return@forEach
        for (k in listOf("currency", "rideTypes")) if (k !in c) out += "cities.$city.$k"
        obj(c["rideTypes"])?.forEach { (rt, rtEl) ->
            val f = obj(rtEl) ?: return@forEach
            for (k in listOf("base", "perKm", "perMinute", "minimumFare")) {
                if (k !in f) out += "cities.$city.rideTypes.$rt.$k"
            }
            for (w in listOf("night", "peak")) {
                obj(f[w])?.let { win ->
                    for (k in listOf("multiplier", "startHourUtc", "endHourUtc")) {
                        if (k !in win) out += "cities.$city.rideTypes.$rt.$w.$k"
                    }
                }
            }
        }
    }
    return out
}

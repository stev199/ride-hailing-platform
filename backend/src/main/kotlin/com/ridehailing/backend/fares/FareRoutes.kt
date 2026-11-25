package com.ridehailing.backend.fares

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.withUser
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
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
            val req = call.receive<FareConfig>()
            when (val r = config.updateFares(user.id, req)) {
                is com.ridehailing.backend.config.ConfigService.UpdateResult.Updated ->
                    call.respond(HttpStatusCode.OK, config.fares)
                is com.ridehailing.backend.config.ConfigService.UpdateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err("invalid_fares", r.code))
            }
        }
    }
}

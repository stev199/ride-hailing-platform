package com.ridehailing.backend.fares

import java.time.Instant

/**
 * P4-1 fare service: computes the rider-facing estimate from the LIVE fares
 * section of the config bundle + OSRM route distance/time, degrading to the
 * calibrated straight-line fallback when the geo stack is unreachable.
 *
 * Determinism contract (spec §pricing "server vs on-device parity within a
 * stated tolerance"): the NUMBERS come exclusively from FareMath (the parity
 * file). The service only picks inputs (route source + surge hour), so both
 * sides compute identical figures for identical inputs - pinned by the shared
 * KAT vectors.
 */
class FareService(
    /** Provider -> live FareConfig (config center owns it; admin patches take effect next call). */
    private val fares: () -> FareConfig,
    private val osrm: OsrmRouteSource?,
    private val clock: () -> Instant = Instant::now,
) {
    data class RideTypeEstimate(
        val rideType: String,
        val currency: String,
        val lowTotal: Double,
        val highTotal: Double,
        val surgeMultiplier: Double,
        val surgeReason: String?,
        val breakdown: FareMath.FareBreakdown,
    )

    data class EstimateResult(
        val city: String,
        val estimates: List<RideTypeEstimate>,
        /** "osrm" when the live route was used, "straight_line" on degraded fallback. */
        val routeSource: String,
        val distanceMeters: Double,
        val durationSeconds: Double,
    )

    sealed class EstimateOutcome {
        data class Ok(val result: EstimateResult) : EstimateOutcome()
        /** No fare configuration for the city, or the city/ride type keys are unknown. */
        data class Invalid(val field: String, val code: String) : EstimateOutcome()
    }

    fun estimate(pickupLat: Double, pickupLon: Double, dropoffLat: Double, dropoffLon: Double, city: String, rideType: String?): EstimateOutcome {
        val cfg = fares()
        val cityFares = cfg.cities[city] ?: return EstimateOutcome.Invalid("city", "unknown_city")
        val rideTypes = if (rideType != null) {
            val f = cityFares.rideTypes[rideType] ?: return EstimateOutcome.Invalid("rideType", "unknown_ride_type")
            mapOf(rideType to f)
        } else {
            cityFares.rideTypes
        }

        val hour = clock().atZone(java.time.ZoneOffset.UTC).hour
        // The route source contract is "null on any failure" (OsrmRouteSource KDoc);
        // a THROWING source degrades the same way - the rider must never see a 5xx
        // because a geo service is down.
        val route = try {
            osrm?.route(pickupLat, pickupLon, dropoffLat, dropoffLon)
        } catch (_: Exception) {
            null
        }
        val routeSource: String
        val routeInput: FareMath.RouteInput
        if (route != null) {
            routeSource = "osrm"
            routeInput = FareMath.RouteInput(route.distanceMeters, route.durationSeconds)
        } else {
            routeSource = "straight_line"
            routeInput = FareMath.straightLineRoute(
                pickupLat, pickupLon, dropoffLat, dropoffLon,
                cfg.straightLine.detourFactor, cfg.straightLine.assumedAvgSpeedKmh,
            )
        }

        val estimates = rideTypes.map { (name, f) ->
            val params = f.toParams()
            val (surgeMult, surgeReason) = FareMath.surgeForHour(params, hour)
            // Max surge for the range high end: the live hour's surge if any,
            // else the larger configured multiplier (offline parity: the device
            // cannot know the hour confidently; the high end must not under-quote).
            val maxSurge = maxOf(surgeMult, params.nightMultiplier, params.peakMultiplier)
            val range = FareMath.estimateRange(params, routeInput, maxSurge)
            RideTypeEstimate(
                rideType = name,
                currency = cityFares.currency,
                lowTotal = range.low.total,
                highTotal = range.high.total,
                surgeMultiplier = surgeMult,
                surgeReason = surgeReason,
                breakdown = range.high,
            )
        }.sortedBy { it.lowTotal }

        return EstimateOutcome.Ok(
            EstimateResult(
                city = city,
                estimates = estimates,
                routeSource = routeSource,
                distanceMeters = routeInput.distanceMeters,
                durationSeconds = routeInput.durationSeconds,
            ),
        )
    }
}

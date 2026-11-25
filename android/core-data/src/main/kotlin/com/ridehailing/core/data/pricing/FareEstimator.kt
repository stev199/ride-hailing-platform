package com.ridehailing.core.data.pricing

import com.ridehailing.core.data.di.ClockMs
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Per-ride-type estimate - same shape as the backend FareService.RideTypeEstimate. */
data class RideTypeEstimate(
    val rideType: String,
    val currency: String,
    val lowTotal: Double,
    val highTotal: Double,
    val surgeMultiplier: Double,
    val surgeReason: String?,
    val breakdown: FareMath.FareBreakdown,
)

data class OfflineEstimateResult(
    val city: String,
    val estimates: List<RideTypeEstimate>,
    /** "cached_route" when a prior online route was reused, "straight_line" on fallback. */
    val routeSource: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val staleConfig: Boolean,
    val bundleVersion: Long?,
    val signatureVerified: Boolean?,
)

sealed class OfflineEstimateOutcome {
    data class Ok(val result: OfflineEstimateResult) : OfflineEstimateOutcome()
    /** Nothing cached yet, or the cached config JSON is unreadable. */
    data class NoConfig(val reason: String) : OfflineEstimateOutcome()
    data class UnknownCity(val city: String) : OfflineEstimateOutcome()
    data class UnknownRideType(val city: String, val rideType: String) : OfflineEstimateOutcome()
}

/**
 * P4-3 on-device estimator - the DEVICE mirror of the backend FareService.estimate.
 *
 * Determinism contract (spec §pricing "server vs on-device parity within a stated
 * tolerance"): the NUMBERS come exclusively from FareMath (the parity file, KAT-pinned).
 * Inputs differ only where offline is the point:
 * - route: [cachedRoute] (meters, seconds - the last online route for this pair, fed by
 *   P4-4 from the backend estimate response) -> "cached_route"; else the calibrated
 *   straight-line fallback -> "straight_line" (same detour/speed the server would use).
 * - config: the cached signed bundle; a stale bundle still estimates but sets
 *   [OfflineEstimateResult.staleConfig] so the UI keeps the disclaimer honest.
 * - surge high end: max(live-hour surge, night, peak) - the offline device cannot be
 *   sure of the hour's window state, so the range never under-quotes (same maxOf the
 *   server applies).
 */
@Singleton
class FareEstimator @Inject constructor(
    private val cache: FareBundleCache,
    @ClockMs private val clockMs: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun estimate(
        pickupLat: Double,
        pickupLon: Double,
        dropoffLat: Double,
        dropoffLon: Double,
        city: String,
        rideType: String? = null,
        cachedRoute: FareMath.RouteInput? = null,
        nowEpochSec: Long = clockMs() / 1000,
    ): OfflineEstimateOutcome {
        val bundle = cache.bundle()
            ?: return OfflineEstimateOutcome.NoConfig("no cached fare bundle - fetch /v1/config while online")
        val cfg = try {
            json.decodeFromString(CanonicalConfigDto.serializer(), bundle.configJson).fares
        } catch (e: Exception) {
            return OfflineEstimateOutcome.NoConfig("cached bundle unreadable: ${e.message}")
        }
        val cityFares = cfg.cities[city] ?: return OfflineEstimateOutcome.UnknownCity(city)
        val rideTypes = if (rideType != null) {
            val f = cityFares.rideTypes[rideType]
                ?: return OfflineEstimateOutcome.UnknownRideType(city, rideType)
            mapOf(rideType to f)
        } else {
            cityFares.rideTypes
        }

        val stale = cache.isStale(bundle, nowEpochSec)
        val hour = Instant.ofEpochSecond(nowEpochSec).atZone(ZoneOffset.UTC).hour
        val (routeSource, routeInput) = if (cachedRoute != null) {
            "cached_route" to cachedRoute
        } else {
            "straight_line" to FareMath.straightLineRoute(
                pickupLat, pickupLon, dropoffLat, dropoffLon,
                cfg.straightLine.detourFactor, cfg.straightLine.assumedAvgSpeedKmh,
            )
        }

        val estimates = rideTypes.map { (name, f) ->
            val params = f.toParams()
            val (surgeMult, surgeReason) = FareMath.surgeForHour(params, hour)
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

        return OfflineEstimateOutcome.Ok(
            OfflineEstimateResult(
                city = city,
                estimates = estimates,
                routeSource = routeSource,
                distanceMeters = routeInput.distanceMeters,
                durationSeconds = routeInput.durationSeconds,
                staleConfig = stale,
                bundleVersion = bundle.version,
                signatureVerified = bundle.signatureVerified,
            ),
        )
    }
}

package com.ridehailing.core.data.pricing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P4-1 fare math - THE PARITY FILE (ANDROID COPY, P4-3).
 *
 * VERBATIM copy of backend/src/main/kotlin/com/ridehailing/backend/fares/FareMath.kt
 * (only the package line changed). ANY change here MUST be mirrored on the backend in
 * the same batch - both sides are pinned to identical outputs by the shared KAT vectors
 * (fare_kat.json - byte-identical file committed under backend and android test
 * resources, SHA-256 pinned in both test suites).
 *
 * Money rules (must match the spec §pricing and the device copy):
 * - All amounts are Double in the city's MAJOR currency unit; rounding happens
 *   ONCE at the end of each computed figure with [roundMoney] = floor(x*100+0.5)/100
 *   (HALF_UP for the always-positive fare amounts; identical IEEE-754 double
 *   arithmetic on JVM and ART, and identical to Python's math.floor for the
 *   independent KAT vector generation).
 * - The estimate shown to riders is a RANGE: low = no surge, high = the ride
 *   type's max surge multiplier applied (deterministic on both sides; the
 *   permanent disclaimer is rendered by the client).
 * - Minimum fare applies AFTER base+distance+time+surge but BEFORE booking fee
 *   (the booking fee is a platform fee, never folded into the minimum).
 * - Surge selection is deterministic from the UTC hour: night window wins if
 *   both windows cover the hour (named reason "night").
 */
object FareMath {

    /** Route measurement the calculator consumes (meters + seconds). */
    data class RouteInput(val distanceMeters: Double, val durationSeconds: Double)

    /** Per-ride-type fare parameters (device-visible copy of the admin config). */
    data class RideTypeParams(
        val base: Double,
        val perKm: Double,
        val perMinute: Double,
        val minimumFare: Double,
        val bookingFee: Double,
        val cancelFee: Double,
        val waitFeePerMinute: Double,
        val nightMultiplier: Double,
        val nightStartHourUtc: Int,
        val nightEndHourUtc: Int,
        val peakMultiplier: Double,
        val peakStartHourUtc: Int,
        val peakEndHourUtc: Int,
    )

    /** One figure of the estimate - the device renders the breakdown verbatim. */
    data class FareBreakdown(
        val base: Double,
        val distanceCost: Double,
        val timeCost: Double,
        val surgeMultiplier: Double,
        val surgeReason: String?,
        val minimumApplied: Boolean,
        val bookingFee: Double,
        val total: Double,
    )

    data class EstimateRange(val low: FareBreakdown, val high: FareBreakdown)

    /**
     * Surge multiplier for a UTC hour. A window where start==end never applies
     * (disabled). Windows may wrap midnight (e.g. 22..6). Night beats peak on
     * overlap so the reason is stable.
     */
    fun surgeForHour(p: RideTypeParams, hourUtc: Int): Pair<Double, String?> {
        val h = hourUtc % 24
        fun covers(start: Int, end: Int): Boolean =
            if (start == end) false else if (start < end) h in start until end else h >= start || h < end
        if (p.nightMultiplier > 1.0 && covers(p.nightStartHourUtc, p.nightEndHourUtc)) {
            return p.nightMultiplier to "night"
        }
        if (p.peakMultiplier > 1.0 && covers(p.peakStartHourUtc, p.peakEndHourUtc)) {
            return p.peakMultiplier to "peak"
        }
        return 1.0 to null
    }

    /** Single deterministic fare figure for a route + surge multiplier. */
    fun calculate(p: RideTypeParams, route: RouteInput, surgeMultiplier: Double): FareBreakdown {
        val distanceCost = (route.distanceMeters / 1000.0) * p.perKm
        val timeCost = (route.durationSeconds / 60.0) * p.perMinute
        val subtotal = p.base + (distanceCost + timeCost) * surgeMultiplier
        val minimumApplied = subtotal < p.minimumFare
        val afterMinimum = if (minimumApplied) p.minimumFare else subtotal
        val total = roundMoney(afterMinimum + p.bookingFee)
        return FareBreakdown(
            base = roundMoney(p.base),
            distanceCost = roundMoney(distanceCost),
            timeCost = roundMoney(timeCost),
            surgeMultiplier = surgeMultiplier,
            surgeReason = if (surgeMultiplier > 1.0) "surged" else null,
            minimumApplied = minimumApplied,
            bookingFee = roundMoney(p.bookingFee),
            total = total,
        )
    }

    /**
     * The rider-facing range: low = no surge, high = the max applicable surge.
     * The reason strings for display come from [surgeForHour]; when the hour is
     * unknown (offline device), the high end still assumes the max multiplier
     * so the range never under-quotes a possible surge.
     */
    fun estimateRange(p: RideTypeParams, route: RouteInput, maxSurgeMultiplier: Double): EstimateRange {
        val low = calculate(p, route, 1.0)
        val high = if (maxSurgeMultiplier > 1.0) calculate(p, route, maxSurgeMultiplier) else low
        return EstimateRange(low, high)
    }

    /**
     * Haversine distance in meters (R=6371008.8, IUGG mean radius). The device
     * uses the same constant so offline straight-line estimates match.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * Offline fallback route: straight-line distance x calibrated detour factor,
     * duration from an assumed average speed (km/h) - both configured in the
     * signed bundle so the operator calibrates, the device never guesses.
     */
    fun straightLineRoute(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
        detourFactor: Double, assumedAvgSpeedKmh: Double,
    ): RouteInput {
        val meters = haversineMeters(lat1, lon1, lat2, lon2) * detourFactor
        val seconds = if (assumedAvgSpeedKmh <= 0.0) {
            0.0
        } else {
            meters / 1000.0 / assumedAvgSpeedKmh * 3600.0
        }
        return RouteInput(meters, seconds)
    }

    /** Money rounding used everywhere in this file: floor(x*100+0.5)/100. */
    fun roundMoney(x: Double): Double = floor(x * 100.0 + 0.5) / 100.0
}

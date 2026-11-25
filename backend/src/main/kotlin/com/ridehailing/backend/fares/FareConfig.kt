package com.ridehailing.backend.fares

import com.ridehailing.backend.config.ZeroFractionIntRequiredSerializer
import kotlinx.serialization.Serializable

/**
 * P4-1 fare configuration - a section of the SIGNED device-visible config bundle
 * (spec §pricing: "a signed, versioned fare-config bundle cached while online
 * (expiry and stale-config flag)" - the device fetches /v1/config and caches the
 * whole bundle; the expiry/stale flag lives device-side in P4-3).
 *
 * Structure: per city (top-level key, e.g. "monaco") a currency and the ride
 * types offered there; per ride type the full fee set from spec §69 (base,
 * per km, per minute, minimum, booking fee, cancel fee, wait fee, night/peak
 * surcharges). `straightLine` carries the offline-estimate calibration so the
 * DEVICE computes the same fallback the server would (operator-calibrated, the
 * device never guesses - spec §pricing "else straight-line x calibrated detour
 * factor").
 *
 * SHAPE CONTRACT: the admin PATCH body (D75 full-replace) and the device bundle
 * use THE SAME nested shape (surge windows are objects, not flat fields), so
 * the two can never drift. The flat primitives exist only inside FareMath (the
 * parity file) and are mapped by [RideTypeFares.toParams].
 *
 * Admin edits replace the WHOLE fares section (D75): the nested per-city map
 * makes field-wise patches explode; the admin UI sends the edited document and
 * the server validates it completely before publishing a new signed version.
 */
@Serializable
data class FareConfig(
    val cities: Map<String, CityFares> = emptyMap(),
    val straightLine: StraightLineCalibration = StraightLineCalibration(),
    val quote: QuotePolicy = QuotePolicy(),
) {
    /** Guard for map sizes: config bundles are device-cached; a hostile patch must not ship a 10MB bundle. */
    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (cities.size > MAX_CITIES) errs += "cities: at most $MAX_CITIES cities"
        for ((city, c) in cities) {
            if (!city.matches(CITY_KEY_RE)) errs += "cities.$city: city key must match $CITY_KEY_DESC"
            if (c.currency.length != 3 || c.currency.any { it !in 'A'..'Z' }) {
                errs += "cities.$city.currency: ISO-4217 code required"
            }
            if (c.rideTypes.isEmpty()) errs += "cities.$city.rideTypes: at least one ride type"
            if (c.rideTypes.size > MAX_RIDE_TYPES_PER_CITY) {
                errs += "cities.$city.rideTypes: at most $MAX_RIDE_TYPES_PER_CITY ride types"
            }
            for ((name, f) in c.rideTypes) {
                val p = "cities.$city.rideTypes.$name"
                if (!name.matches(CITY_KEY_RE)) errs += "$p: ride type key must match $CITY_KEY_DESC"
                f.negativeField()?.let { errs += "$p.$it: must be >= 0" }
                for ((label, w) in listOf("night" to f.night, "peak" to f.peak)) {
                    if (w.multiplier < 1.0 || w.multiplier > MAX_SURGE) errs += "$p.$label.multiplier: must be in 1.0..$MAX_SURGE"
                    if (w.startHourUtc !in 0..23) errs += "$p.$label.startHourUtc: must be in 0..23 (start==end disables the window)"
                    if (w.endHourUtc !in 0..23) errs += "$p.$label.endHourUtc: must be in 0..23 (start==end disables the window)"
                }
            }
        }
        if (straightLine.detourFactor < 1.0 || straightLine.detourFactor > MAX_DETOUR) {
            errs += "straightLine.detourFactor: must be in 1.0..$MAX_DETOUR"
        }
        if (straightLine.assumedAvgSpeedKmh <= 0.0 || straightLine.assumedAvgSpeedKmh > MAX_SPEED_KMH) {
            errs += "straightLine.assumedAvgSpeedKmh: must be in (0..$MAX_SPEED_KMH]"
        }
        if (quote.validitySeconds !in 30..3600) errs += "quote.validitySeconds: must be in 30..3600"
        if (quote.mode !in setOf("upfront_locked", "metered_capped")) {
            errs += "quote.mode: must be upfront_locked or metered_capped"
        }
        if (quote.meteredCapMultiplier < 1.0 || quote.meteredCapMultiplier > 3.0) {
            errs += "quote.meteredCapMultiplier: must be in 1.0..3.0"
        }
        return errs
    }

    companion object {
        const val MAX_CITIES = 16
        const val MAX_RIDE_TYPES_PER_CITY = 16
        const val MAX_SURGE = 5.0
        const val MAX_DETOUR = 3.0
        const val MAX_SPEED_KMH = 200.0
        /** Lowercase kebab keys keep URLs and device cache keys unambiguous. */
        val CITY_KEY_RE = Regex("[a-z][a-z0-9-]{0,31}")
        const val CITY_KEY_DESC = "lowercase letters/digits/hyphen (max 32)"
    }
}

@Serializable
data class CityFares(
    val currency: String,
    val rideTypes: Map<String, RideTypeFares>,
)

/**
 * Fee set per city+ride type (spec §69 field names). All amounts in the city's
 * MAJOR currency unit (e.g. 2.50 = 2 EUR 50); multipliers are unitless.
 */
@Serializable
data class RideTypeFares(
    val base: Double,
    val perKm: Double,
    val perMinute: Double,
    val minimumFare: Double,
    val bookingFee: Double = 0.0,
    val cancelFee: Double = 0.0,
    val waitFeePerMinute: Double = 0.0,
    val night: SurgeWindow = SurgeWindow(),
    val peak: SurgeWindow = SurgeWindow(),
) {
    /** First negative amount field name, or null (used by validation). */
    fun negativeField(): String? = when {
        base < 0 -> "base"
        perKm < 0 -> "perKm"
        perMinute < 0 -> "perMinute"
        minimumFare < 0 -> "minimumFare"
        bookingFee < 0 -> "bookingFee"
        cancelFee < 0 -> "cancelFee"
        waitFeePerMinute < 0 -> "waitFeePerMinute"
        else -> null
    }

    /** Bridge to the pure parity math (FareMath takes positional primitives only). */
    fun toParams(): FareMath.RideTypeParams = FareMath.RideTypeParams(
        base = base, perKm = perKm, perMinute = perMinute, minimumFare = minimumFare,
        bookingFee = bookingFee, cancelFee = cancelFee, waitFeePerMinute = waitFeePerMinute,
        nightMultiplier = night.multiplier, nightStartHourUtc = night.startHourUtc, nightEndHourUtc = night.endHourUtc,
        peakMultiplier = peak.multiplier, peakStartHourUtc = peak.startHourUtc, peakEndHourUtc = peak.endHourUtc,
    )
}

/**
 * Surge window (UTC hours). start==end DISABLES the window; windows may wrap
 * midnight (22..6). Hour fields parse integral floats per JSON Schema "integer"
 * semantics (ST202 lesson, required-field serializer).
 */
@Serializable
data class SurgeWindow(
    val multiplier: Double = 1.0,
    @Serializable(with = ZeroFractionIntRequiredSerializer::class) val startHourUtc: Int = 0,
    @Serializable(with = ZeroFractionIntRequiredSerializer::class) val endHourUtc: Int = 0,
)

@Serializable
data class StraightLineCalibration(
    val detourFactor: Double = 1.3,
    val assumedAvgSpeedKmh: Double = 22.0,
)

/**
 * P4-2 final-quote policy (spec §pricing: "an admin-configurable validity window and
 * policy (upfront-locked or metered with cap)"). upfront_locked = the rider books at
 * the quoted amount; metered_capped = the final metered price is capped at
 * meteredCapMultiplier x the quoted projection (cap enforcement lands with the wallet
 * holds in Phase 7 - the quote document carries the cap either way).
 */
@Serializable
data class QuotePolicy(
    val validitySeconds: Int = 90,
    val mode: String = "upfront_locked",
    val meteredCapMultiplier: Double = 1.5,
)

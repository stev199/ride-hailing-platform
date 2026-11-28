package com.ridehailing.backend.config

import kotlinx.serialization.Serializable

/**
 * P7-6 promo codes section of the device-visible signed config bundle (D75
 * full-replace like fares/wallet). A promo reduces the CHARGE at booking:
 * PERCENT = percent of the signed fare, AMOUNT = fixed minor-unit reduction of
 * that currency. The quote signature keeps covering the FULL fare - the promo is
 * a platform-side reduction (favorable to the rider, audited) and the ride stores
 * both quoteAmountMinor (signed) and discountMinor (charge = quote - discount).
 */
@Serializable
data class PromoCodeConfig(
    /** Canonical code: uppercase A-Z0-9 (3..24 chars), unique across the section. */
    val code: String,
    /** "PERCENT" | "AMOUNT". */
    val kind: String,
    /** PERCENT: 0 < value <= 100. AMOUNT: minor units 1..1e18. */
    val value: Double,
    /** Required for AMOUNT (must match the ride currency at apply time). */
    val currency: String? = null,
    val validFromEpochSec: Long = 0,
    val validUntilEpochSec: Long,
    /** Times ONE user may book with this code. */
    val perUserLimit: Int = 1,
    val active: Boolean = true,
) {
    companion object {
        const val KIND_PERCENT = "PERCENT"
        const val KIND_AMOUNT = "AMOUNT"
        const val MONEY_CAP = 1_000_000_000_000_000_000L
    }

    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (!Regex("^[A-Z0-9]{3,24}$").matches(code)) errs += "code: uppercase A-Z0-9 3..24 required"
        when (kind) {
            KIND_PERCENT -> if (value <= 0.0 || value > 100.0) errs += "value: percent must be 0 < value <= 100"
            KIND_AMOUNT -> {
                if (value <= 0.0 || value > MONEY_CAP.toDouble()) errs += "value: amount must be 0 < value <= 1e18"
                if (currency.isNullOrBlank() || currency.length > 12) errs += "currency: required for AMOUNT (<= 12 chars)"
            }
            else -> errs += "kind: PERCENT or AMOUNT required"
        }
        if (validFromEpochSec < 0) errs += "validFromEpochSec: >= 0 required"
        // Round-31 ST202 (round-24b parity): NO cross-field window rule - the openapi
        // schema cannot express validUntil > validFrom, and a rule the spec cannot
        // express would reject schema-valid fuzz bodies (an unordered window is dead
        // config that simply never applies - harmless, not invalid).
        if (validUntilEpochSec < 1) errs += "validUntilEpochSec: >= 1 required"
        if (perUserLimit < 1) errs += "perUserLimit: >= 1 required"
        return errs
    }

    /** The discount in minor units for a fare (invalid kind -> 0; never negative). */
    fun discountFor(fareMinor: Long): Long = when (kind) {
        KIND_PERCENT -> {
            val d = fareMinor * value / 100.0
            d.toLong().coerceIn(0, fareMinor)
        }
        KIND_AMOUNT -> value.toLong().coerceIn(0, fareMinor)
        else -> 0
    }
}

@Serializable
data class PromoConfig(
    val codes: List<PromoCodeConfig> = emptyList(),
) {
    companion object {
        const val MAX_CODES = 50
    }

    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (codes.size > MAX_CODES) errs += "codes: at most $MAX_CODES codes"
        val seen = mutableSetOf<String>()
        for (c in codes) {
            if (!seen.add(c.code)) errs += "codes: duplicate code ${c.code}"
            errs += c.validationErrors().map { "${c.code}.$it" }
        }
        return errs
    }

    fun byCode(code: String): PromoCodeConfig? = codes.firstOrNull { it.code == code.uppercase().trim() }
}

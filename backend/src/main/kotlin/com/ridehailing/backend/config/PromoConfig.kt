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
    /** PERCENT: 0 < value <= 100. AMOUNT: minor units 1..1e18 (boundary doubles clamp to the cap, round-33). */
    val value: Double,
    /** Required for AMOUNT (must match the ride currency at apply time); <= 12 chars for ALL kinds (openapi maxLength). */
    val currency: String? = null,
    val validFromEpochSec: Long = 0,
    val validUntilEpochSec: Long,
    /** Times ONE user may book with this code (openapi schema: 1..1000). */
    val perUserLimit: Int = 1,
    val active: Boolean = true,
) {
    companion object {
        const val KIND_PERCENT = "PERCENT"
        const val KIND_AMOUNT = "AMOUNT"
        const val MONEY_CAP = 1_000_000_000_000_000_000L

        /** A JSON number written without a decimal point or exponent (exact integer text). */
        private val INTEGER_TEXT = Regex("-?[0-9]+")

        /** The 1e18 money cap as an exact decimal (no double rounding on the comparison). */
        private val MONEY_CAP_DECIMAL = java.math.BigDecimal.valueOf(MONEY_CAP.toLong())

        /** Double precision = 15 significant decimal digits (IEEE754 binary64 round-trip guarantee). */
        private val DOUBLE_PRECISION = java.math.MathContext(15, java.math.RoundingMode.HALF_UP)

        /**
         * Round-33 ST202 (CI case eDh6YV): boundary-double leniency at the 1e18 money
         * cap, consistent with the round-24b integral-float leniency. Schemathesis
         * generates shortest-repr IEEE754 doubles: for `1.0000000000000001e+18` the
         * decimal value is 1e18+100, whose nearest double is 1e18+128 - STRICTLY above
         * the cap as bits - yet its DECIMAL FORM reads as the cap at double precision
         * (15 significant digits). The DECIMAL FORM decides, in order:
         * - exact decimal <= 1e18 -> the parsed double (in cap, integer or float text);
         * - INTEGER TEXT above the cap -> null (round-24c int64-boundary guard parity:
         *   exact comparison, NO leniency - an exact integer amount above the cap is a
         *   real overflow, not a rounding artifact; the double of 1000000000000000001
         *   is exactly 1e18, so the bits alone cannot catch it);
         * - float text whose decimal form rounds to <= 1e18 at 15 significant digits
         *   -> MONEY_CAP.toDouble() (CLAMPED to the cap so the config store can never
         *   hold an above-cap value);
         * - everything else (1e21, non-finite, unparseable) -> null (400).
         */
        fun amountValueInMoneyCap(text: String): Double? {
            val parsed = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val exact = java.math.BigDecimal(text)
            if (exact.compareTo(MONEY_CAP_DECIMAL) <= 0) return parsed
            if (INTEGER_TEXT.matches(text)) return null
            val rounded = exact.round(DOUBLE_PRECISION)
            return if (rounded.compareTo(MONEY_CAP_DECIMAL) <= 0) MONEY_CAP.toDouble() else null
        }
    }

    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (!Regex("^[A-Z0-9]{3,24}$").matches(code)) errs += "code: uppercase A-Z0-9 3..24 required"
        when (kind) {
            KIND_PERCENT -> if (value <= 0.0 || value > 100.0) errs += "value: percent must be 0 < value <= 100"
            KIND_AMOUNT -> {
                if (value <= 0.0 || value > MONEY_CAP.toDouble()) errs += "value: amount must be 0 < value <= 1e18"
                if (currency.isNullOrBlank()) errs += "currency: required for AMOUNT"
            }
            else -> errs += "kind: PERCENT or AMOUNT required"
        }
        // Round-34: the schema currency bound is NOT kind-conditional - the
        // PromoCodeSection currency is { type: [string, 'null'], maxLength: 12 } for
        // every kind (the AMOUNT if/then only adds the minLength-1 requirement). The
        // round-24c-era check guarded AMOUNT only, so a PERCENT code could STORE a
        // schema-invalid currency and ECHO it in GET/PATCH responses (the round-33
        // perUserLimit lesson: the single write path must keep the store in-bounds).
        if (currency != null && currency.length > 12) errs += "currency: <= 12 chars required"
        if (validFromEpochSec < 0) errs += "validFromEpochSec: >= 0 required"
        // Round-31 ST202 (round-24b parity): NO cross-field window rule - the openapi
        // schema cannot express validUntil > validFrom, and a rule the spec cannot
        // express would reject schema-valid fuzz bodies (an unordered window is dead
        // config that simply never applies - harmless, not invalid).
        if (validUntilEpochSec < 1) errs += "validUntilEpochSec: >= 1 required"
        // Round-33 ST108/ST201 (CI XT4gZF + QK4V6R): the schema maximum was missing
        // server-side - a PATCH with perUserLimit 1001 was ACCEPTED and then ECHOED in
        // GET/PATCH responses (the round-24d wallet maximums lesson, promos edition).
        if (perUserLimit < 1 || perUserLimit > 1000) errs += "perUserLimit: 1..1000 required"
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

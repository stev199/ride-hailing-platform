package com.ridehailing.backend.config

import com.ridehailing.backend.lengthInCodePoints
import kotlinx.serialization.Serializable

/**
 * P8-1 cancellation section of the device-visible signed config bundle (D75
 * full-replace like fares/wallet/promos). Governs the two rider cancel windows and
 * the reason catalogs the driver/rider apps offer at cancel time:
 *
 * - freeCancelWindowSec: a REQUESTED ride cancelled within this many seconds of its
 *   booking (createdAtEpochSec) is fee-free; past it the ride type's configured
 *   cancelFee posts to the ledger. POST-ACCEPT cancels (ACCEPTED/ARRIVED - driver
 *   en route) have NO free window: the fee always applies (P8-1 decision).
 * - riderReasons / driverReasons: the reason catalogs shown at cancel time. P8-1
 *   DECISION (round note): catalog membership is enforced ONLY when the catalog is
 *   NON-EMPTY - an empty catalog means free-text reasons are accepted verbatim
 *   (bounded by the per-request 200-char cap, openapi CancelRequest.maxLength).
 *   Deployments opt into strict reasons by publishing entries; the default
 *   deployment stays free-text so the v1 clients keep working untouched.
 */
@Serializable
data class CancellationConfig(
    /** Fee-free cancel window from booking time, seconds (0 = pay to cancel immediately). */
    val freeCancelWindowSec: Long = 60L,
    /** Rider reason catalog (empty = free-text accepted). */
    val riderReasons: List<String> = emptyList(),
    /** Driver reason catalog (empty = free-text accepted; non-empty makes a reason REQUIRED for driver cancels). */
    val driverReasons: List<String> = emptyList(),
) {
    companion object {
        const val MIN_FREE_WINDOW_SEC = 0L
        const val MAX_REASONS = 20
        const val MIN_REASON_CHARS = 2
        const val MAX_REASON_CHARS = 120
        /** Per-request reason cap (openapi CancelRequest.reason.maxLength, both roles). */
        const val MAX_REQUEST_REASON_CHARS = 200
    }

    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (freeCancelWindowSec < MIN_FREE_WINDOW_SEC) errs += "freeCancelWindowSec: >= 0 required"
        // Per-field bounds only, mirroring the schema exactly (D49: a rule the spec
        // cannot express would reject schema-valid fuzz bodies - ST202 lesson). No
        // cross-list or uniqueness rule: duplicate catalog entries are dead config
        // that simply never matches, not invalid config.
        for (list in listOf("riderReasons" to riderReasons, "driverReasons" to driverReasons)) {
            val (name, reasons) = list
            if (reasons.size > MAX_REASONS) errs += "$name: at most $MAX_REASONS reasons"
            for (r in reasons) {
                // Round-37: code points, not UTF-16 units (surrogate pairs shrink
                // the code-point count below the unit count).
                if (r.lengthInCodePoints() < MIN_REASON_CHARS || r.lengthInCodePoints() > MAX_REASON_CHARS) {
                    errs += "$name: each reason must be $MIN_REASON_CHARS..$MAX_REASON_CHARS chars"
                }
            }
        }
        return errs
    }
}

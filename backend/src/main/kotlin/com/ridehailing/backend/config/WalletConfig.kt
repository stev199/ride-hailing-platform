package com.ridehailing.backend.config

import kotlinx.serialization.Serializable

/**
 * P7-1 wallet section of the device-visible signed config bundle (spec §pricing/
 * §wallet: "limits/fees from admin config"). D75 FULL-REPLACE semantics like the
 * fares section: the admin sends the edited document, the server validates it
 * completely; invalid configs never touch live state and never burn a version.
 */
@Serializable
data class WalletConfig(
    /** Transfer fee in basis points of the transferred amount (0 = free transfers). */
    val transferFeeBps: Int = 0,
    /** Minimum points transfer in minor units (anti-dust). */
    val minTransferMinor: Long = 1L,
    /** Rolling per-user daily transfer cap in minor units. */
    val dailyTransferLimitMinor: Long = 100_000L,
    /** Platform commission on completed WALLET rides, bps of the fare (P7-2). */
    val commissionBps: Int = 0,
) {
    fun validationErrors(): List<String> {
        val errs = mutableListOf<String>()
        if (transferFeeBps < 0 || transferFeeBps > 1000) errs += "transferFeeBps: 0..1000 required"
        if (minTransferMinor < 1) errs += "minTransferMinor: >= 1 required"
        // Per-field bounds only (D49 generator precision): the cross-field
        // daily>=min rule is NOT enforced - the spec cannot express it, and a
        // rule the spec cannot express would reject schema-valid fuzz bodies
        // (ST202). dailyTransferLimitMinor still has its OWN per-field minimum.
        if (dailyTransferLimitMinor < 1) errs += "dailyTransferLimitMinor: >= 1 required"
        if (commissionBps < 0 || commissionBps > 3000) errs += "commissionBps: 0..3000 required"
        return errs
    }
}

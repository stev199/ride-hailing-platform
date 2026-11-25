package com.ridehailing.backend.rides

import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.fares.FareMath
import java.time.Instant

/**
 * P4-2 ride domain + the server-SIGNED final quote (spec §pricing: "Final quote
 * (authoritative): always computed by the server, signed, with an admin-configurable
 * validity window and policy (upfront-locked or metered with cap). ... Wallet holds
 * happen server-side at confirmation" - holds land in Phase 7; the v1 quote carries
 * the amount + policy + signature and the ride state machine guards what may happen
 * to it).
 *
 * Quote canonical payload (signed with the config-bundle Ed25519 key, domain string
 * "ride.quote" separating it from config bundle signatures):
 *   {riderId, city, rideType, currency, amountMinor, issuedAtEpochSec,
 *    validUntilEpochSec, mode, meteredCapMinor, pickupLat, pickupLon,
 *    dropoffLat, dropoffLon}
 * The coordinates are part of the quoted trip - a changed pickup invalidates the quote.
 */
object RideQuote {

    const val DOMAIN = "ride.quote"

    data class Quote(
        val payloadCanonicalJson: String,
        val signatureHex: String,
        val amountMinor: Long,
        val currency: String,
        val mode: String,
        val meteredCapMinor: Long,
        val validUntilEpochSec: Long,
        val issuedAtEpochSec: Long,
    )

    /**
     * Compute + sign the final quote for a route at the CURRENT hour surge.
     * upfront_locked: amountMinor = the exact current fare (the rider books at it).
     * metered_capped: amountMinor = the projected fare; meteredCapMinor =
     * amountMinor x cap multiplier (cap enforcement Phase 7 - the document carries
     * the cap either way so the device can render "at most X").
     */
    fun forRide(
        bundle: com.ridehailing.backend.config.SignedConfigBundle,
        riderId: String,
        city: String,
        rideType: String,
        currency: String,
        params: FareMath.RideTypeParams,
        route: FareMath.RouteInput,
        pickupLat: Double,
        pickupLon: Double,
        dropoffLat: Double,
        dropoffLon: Double,
        policy: com.ridehailing.backend.fares.QuotePolicy,
        now: Instant,
    ): Quote {
        val (surge, _) = FareMath.surgeForHour(params, now.atZone(java.time.ZoneOffset.UTC).hour)
        val breakdown = FareMath.calculate(params, route, surge)
        val amountMinor = Math.round(breakdown.total * 100.0)
        val validUntil = now.epochSecond + policy.validitySeconds
        val capMinor = Math.round(amountMinor * policy.meteredCapMultiplier)
        val canonical = EventChain.canonicalJson(
            mapOf(
                "riderId" to riderId,
                "city" to city,
                "rideType" to rideType,
                "currency" to currency,
                "amountMinor" to amountMinor,
                "issuedAtEpochSec" to now.epochSecond,
                "validUntilEpochSec" to validUntil,
                "mode" to policy.mode,
                "meteredCapMinor" to capMinor,
                "pickupLat" to pickupLat,
                "pickupLon" to pickupLon,
                "dropoffLat" to dropoffLat,
                "dropoffLon" to dropoffLon,
            ),
        )
        return Quote(
            payloadCanonicalJson = canonical,
            signatureHex = (bundle.signPayload(DOMAIN, canonical) ?: ByteArray(0)).toHex(),
            amountMinor = amountMinor,
            currency = currency,
            mode = policy.mode,
            meteredCapMinor = capMinor,
            validUntilEpochSec = validUntil,
            issuedAtEpochSec = now.epochSecond,
        )
    }

    /** Independent verification (device-side contract; used by tests as the round-trip proof). */
    fun verify(
        bundle: com.ridehailing.backend.config.SignedConfigBundle,
        payloadCanonicalJson: String,
        signatureHex: String,
    ): Boolean = bundle.verifyPayload(DOMAIN, payloadCanonicalJson, signatureHex.fromHex())

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

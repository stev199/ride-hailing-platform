package com.ridehailing.backend.rides

/**
 * P4-2 ride model + store port (same port pattern as the P1-3 user/device stores:
 * in-memory default, PG-backed in CI - RideStoreContractTest runs the shared
 * scenarios against BOTH).
 */
data class Ride(
    val id: String,
    val riderId: String,
    /** Assigned driver (P5): null until a driver CLAIMS the ride (single-winner). */
    val driverId: String? = null,
    val city: String,
    val rideType: String,
    val status: RideStatus,
    val paymentMethod: String,
    val note: String?,
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val pickupName: String?,
    val dropoffName: String?,
    val quoteJson: String,
    val quoteSignature: String,
    val quoteAmountMinor: Long,
    val quoteCurrency: String,
    val quoteMode: String,
    val quoteMeteredCapMinor: Long,
    val quoteIssuedAtEpochSec: Long,
    val quoteValidUntilEpochSec: Long,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
    /** P7-6: promo code applied at booking (null = full fare charged). */
    val promoCode: String? = null,
    /** P7-6: platform-side reduction of the signed fare (charge = quote - discount). */
    val discountMinor: Long = 0,
) {
    /** The amount actually charged (held/captured for WALLET, owed for CASH). */
    val chargeAmountMinor: Long get() = quoteAmountMinor - discountMinor
}

interface RideStore {
    /** Insert a new ride (status REQUESTED) with its pickup/dropoff stop rows. */
    fun insert(ride: Ride): Ride

    fun byId(id: String): Ride?

    /**
     * Persist a legal state transition ([RideStatus.requireTransitionTo] is checked by
     * the SERVICE - the store persists). cancelFeeMinor records the fee applied at
     * cancellation (0 = none); nowEpochSec comes from the service clock (deterministic
     * tests, no hidden store clock).
     */
    fun updateStatus(id: String, status: RideStatus, nowEpochSec: Long, cancelFeeMinor: Long = 0): Ride?

    /** Newest first. */
    fun forRider(riderId: String, limit: Int, offset: Int): List<Ride>

    /**
     * Dispatch candidates (P5): REQUESTED rides, oldest first (fairness: the longest
     * waiting ride shows first; distance sorting happens in the service, which knows
     * the driver's position).
     */
    fun available(limit: Int = 50): List<Ride>

    /**
     * ATOMIC single-winner claim (P5): REQUESTED -> ACCEPTED + driverId. Returns null
     * when the ride is not REQUESTED anymore (another driver won, or it expired) -
     * two drivers racing MUST yield exactly one winner (spec edge case list).
     */
    fun claimForDriver(id: String, driverId: String, nowEpochSec: Long): Ride?

    /** Rides assigned to a driver, newest first. */
    fun forDriver(driverId: String, limit: Int, offset: Int): List<Ride>

    /**
     * Admin trips listing (Phase 6 "Trips: live and historical"): ALL rides newest
     * first with optional status/city filters. Offset pagination (same contract
     * as forRider). Read-only - the ride state machine stays driver/rider-owned.
     */
    fun listAll(status: RideStatus?, city: String?, limit: Int, offset: Int): List<Ride>

    /** P7-6: how many rides THIS rider already booked with THIS promo code (per-user limit). */
    fun countPromoUses(code: String, riderId: String): Int
}

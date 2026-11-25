package com.ridehailing.backend.rides

/**
 * P4-2 ride model + store port (same port pattern as the P1-3 user/device stores:
 * in-memory default, PG-backed in CI - RideStoreContractTest runs the shared
 * scenarios against BOTH).
 */
data class Ride(
    val id: String,
    val riderId: String,
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
)

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
}

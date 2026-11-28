package com.ridehailing.core.data.network

import kotlinx.serialization.Serializable

/**
 * P5 driver wire DTOs - byte-mirrors of the backend driver contract (DriverRoutes.kt).
 * DRIVER-DTO PARITY (spec verification item): these carry NO rider PII - the driver
 * sees pickup/dropoff, the quote and the trip state, never the rider identity.
 */
@Serializable
data class DriverDutyRequestDto(val online: Boolean, val lat: Double, val lon: Double)

@Serializable
data class DriverDutyDto(
    val online: Boolean,
    val lat: Double,
    val lon: Double,
    val updatedAtEpochSec: Long,
)

@Serializable
data class DriverQuoteDto(
    val amountMinor: Long,
    val currency: String,
    val mode: String,
    val meteredCapMinor: Long,
    val validUntilEpochSec: Long,
)

@Serializable
data class DriverRideDto(
    val id: String,
    val status: String,
    val city: String,
    val rideType: String,
    val paymentMethod: String,
    val note: String?,
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val pickupName: String?,
    val dropoffName: String?,
    val quote: DriverQuoteDto,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
    /** Straight-line distance from the driver's duty position (available board only). */
    val distanceMeters: Double? = null,
    /** P7-6: quote minus promo discount = what the rider actually pays. */
    val chargeAmountMinor: Long = 0,
)

@Serializable
data class DriverRideListDto(
    val rides: List<DriverRideDto>,
    val limit: Int,
    val offset: Int,
)

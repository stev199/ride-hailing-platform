package com.ridehailing.core.data.network

import kotlinx.serialization.Serializable

/**
 * P4-4 rides wire DTOs - byte-mirrors of the backend rides contract (RideRoutes.kt,
 * P4-2) and the fare estimate contract (FareRoutes.kt, P4-1). Unknown keys are ignored
 * (D46) so the backend can add fields ahead of the app release.
 */
@Serializable
data class RideCreateRequestDto(
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val city: String,
    val rideType: String,
    val paymentMethod: String = "CASH",
    val note: String? = null,
    val pickupName: String? = null,
    val dropoffName: String? = null,
    /** P7-6: optional promo code - the server validates and discounts the charge. */
    val promoCode: String? = null,
)

@Serializable
data class QuoteDto(
    val amountMinor: Long,
    val currency: String,
    val mode: String,
    val meteredCapMinor: Long,
    val issuedAtEpochSec: Long,
    val validUntilEpochSec: Long,
    val payload: String,
    val signature: String,
    val publicKeyHex: String,
)

@Serializable
data class RideDto(
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
    val quote: QuoteDto,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
    /** P7-6: promo applied at booking (null = full fare). */
    val promoCode: String? = null,
    val discountMinor: Long = 0,
    /** quote.amountMinor - discountMinor = the amount actually charged. */
    val chargeAmountMinor: Long = 0,
)

@Serializable
data class RideListDto(
    val rides: List<RideDto>,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class FareEstimateRequestDto(
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val city: String,
    val rideType: String? = null,
)

@Serializable
data class FareBreakdownDto(
    val base: Double,
    val distanceCost: Double,
    val timeCost: Double,
    val surgeMultiplier: Double,
    val surgeReason: String?,
    val minimumApplied: Boolean,
    val bookingFee: Double,
    val total: Double,
)

@Serializable
data class RideTypeEstimateDto(
    val rideType: String,
    val currency: String,
    val lowTotal: Double,
    val highTotal: Double,
    val surgeMultiplier: Double,
    val surgeReason: String?,
    val breakdown: FareBreakdownDto,
)

@Serializable
data class FareEstimateResponseDto(
    val city: String,
    val routeSource: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val estimates: List<RideTypeEstimateDto>,
    /** Permanent rider-facing disclaimer key - the client renders localized text. */
    val disclaimer: String = "estimated_price_not_final",
)

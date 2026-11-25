package com.ridehailing.core.map

import com.ridehailing.core.data.network.FareBreakdownDto
import com.ridehailing.core.data.network.RideTypeEstimateDto
import com.ridehailing.core.data.pricing.FareMath
import com.ridehailing.core.data.pricing.RideTypeEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM mapping: wire/offline estimate rows -> UI rows (P4-4). The composable renders
 * labels/testTags; here we pin the NUMBERS pass through untouched and the breakdown
 * lines carry STABLE keys (the localized-label contract).
 */
class BookingUiMapperTest {

    private val online = RideTypeEstimateDto(
        rideType = "standard", currency = "EUR",
        lowTotal = 18.82, highTotal = 24.75, surgeMultiplier = 1.4, surgeReason = "night",
        breakdown = FareBreakdownDto(
            base = 2.5, distanceCost = 9.42, timeCost = 5.4,
            surgeMultiplier = 1.4, surgeReason = "night", minimumApplied = false,
            bookingFee = 1.5, total = 24.75,
        ),
    )

    private val offline = RideTypeEstimate(
        rideType = "van", currency = "EUR",
        lowTotal = 24.26, highTotal = 24.26, surgeMultiplier = 1.0, surgeReason = null,
        breakdown = FareMath.FareBreakdown(
            base = 3.0, distanceCost = 12.56, timeCost = 7.2, surgeMultiplier = 1.0,
            surgeReason = null, minimumApplied = false, bookingFee = 1.5, total = 24.26,
        ),
    )

    @Test
    fun onlineMapping_passesNumbersThrough_withStableKeys() {
        val ui = BookingUiMapper.fromOnline(online)
        assertEquals("standard", ui.rideType)
        assertEquals("EUR", ui.currency)
        assertEquals(18.82, ui.lowMajor, 0.0)
        assertEquals(24.75, ui.highMajor, 0.0)
        assertEquals("night", ui.surgeReason)
        assertEquals(24.75, ui.totalMajor, 0.0)
        assertEquals(false, ui.minimumApplied)
        assertEquals(
            listOf("base", "distance", "time", "booking_fee"),
            ui.breakdown.map { it.key },
        )
        assertEquals(2.5, ui.breakdown[0].amountMajor, 0.0)
        assertEquals(1.5, ui.breakdown[3].amountMajor, 0.0)
    }

    @Test
    fun offlineMapping_passesNumbersThrough_andClearsReason() {
        val ui = BookingUiMapper.fromOffline(offline)
        assertEquals("van", ui.rideType)
        assertEquals(24.26, ui.lowMajor, 0.0)
        assertEquals(24.26, ui.highMajor, 0.0)
        assertNull(ui.surgeReason)
        assertEquals(24.26, ui.totalMajor, 0.0)
        assertEquals(listOf("base", "distance", "time", "booking_fee"), ui.breakdown.map { it.key })
    }
}

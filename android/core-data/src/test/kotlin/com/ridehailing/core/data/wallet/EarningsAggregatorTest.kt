package com.ridehailing.core.data.wallet

import com.ridehailing.core.data.network.DriverQuoteDto
import com.ridehailing.core.data.network.DriverRideDto
import com.ridehailing.core.data.network.WalletHistoryItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7-4 earnings aggregation math (pure JVM): cash/wallet split, UTC day buckets,
 * commission derivation from the ledger capture credit, honest pending handling.
 */
class EarningsAggregatorTest {

    private fun ride(
        id: String,
        method: String = "WALLET",
        status: String = "COMPLETED",
        fareMinor: Long = 1_000L,
        updatedAtSec: Long,
        city: String = "monaco",
    ): DriverRideDto = DriverRideDto(
        id = id, status = status, city = city, rideType = "standard", paymentMethod = method,
        note = null, pickupLat = 0.0, pickupLon = 0.0, dropoffLat = 1.0, dropoffLon = 1.0,
        pickupName = null, dropoffName = null,
        quote = DriverQuoteDto(
            amountMinor = fareMinor, currency = "POINTS", mode = "upfront_locked",
            meteredCapMinor = 1_500L, validUntilEpochSec = updatedAtSec,
        ),
        cancelFeeMinor = 0L, createdAtEpochSec = updatedAtSec - 600, updatedAtEpochSec = updatedAtSec,
    )

    private fun capture(rideId: String, amountMinor: Long, atSec: Long): WalletHistoryItemDto =
        WalletHistoryItemDto(
            entryId = "e-$rideId", transactionId = "t-$rideId", amountMinor = amountMinor,
            direction = "CREDIT", reason = "wallet.capture", refType = "ride.completed",
            refId = rideId, createdAtEpochSec = atSec,
        )

    /** Day 10 (epoch) 23:59 vs day 11 00:01 - the UTC-day boundary must split them. */
    private val day10_2359 = 10L * 86_400L + 86_399L
    private val day11_0001 = 11L * 86_400L + 60L

    @Test
    fun cashOnly_todayBucketsByUtcDay() {
        val s = EarningsAggregator.summarize(
            rides = listOf(
                ride("r1", method = "CASH", fareMinor = 700, updatedAtSec = day10_2359),
                ride("r2", method = "CASH", fareMinor = 300, updatedAtSec = day11_0001),
            ),
            history = emptyList(),
            nowEpochSec = day11_0001,
        )
        assertEquals(300L, s.todayCashMinor)
        assertEquals(1_000L, s.weekCashMinor)
        assertEquals(1, s.todayTrips)
        assertEquals(2, s.weekTrips)
        assertEquals(0L, s.todayWalletMinor)
    }

    @Test
    fun walletCommission_derivedFromCredit() {
        val s = EarningsAggregator.summarize(
            rides = listOf(ride("r1", method = "WALLET", fareMinor = 1_000, updatedAtSec = day11_0001)),
            history = listOf(capture("r1", amountMinor = 900, atSec = day11_0001)),
            nowEpochSec = day11_0001,
        )
        val t = s.trips.single()
        assertEquals(900L, t.walletCreditMinor)
        assertEquals(100L, t.commissionMinor)
        assertEquals(900L, s.todayWalletMinor)
    }

    @Test
    fun walletCreditMissing_surfacesAsPending_notMoney() {
        val s = EarningsAggregator.summarize(
            rides = listOf(ride("r1", method = "WALLET", fareMinor = 1_000, updatedAtSec = day11_0001)),
            history = emptyList(),
            nowEpochSec = day11_0001,
        )
        val t = s.trips.single()
        assertNull(t.walletCreditMinor)
        assertEquals(0L, t.commissionMinor)
        assertEquals(0L, s.todayWalletMinor)
    }

    @Test
    fun commissionClamped_atZero_whenCreditExceedsFare() {
        val s = EarningsAggregator.summarize(
            rides = listOf(ride("r1", method = "WALLET", fareMinor = 500, updatedAtSec = day11_0001)),
            history = listOf(capture("r1", amountMinor = 600, atSec = day11_0001)),
            nowEpochSec = day11_0001,
        )
        assertEquals(0L, s.trips.single().commissionMinor)
    }

    @Test
    fun nonCaptureOrForeignCredits_areIgnored() {
        val foreign = WalletHistoryItemDto(
            entryId = "e-x", transactionId = "t-x", amountMinor = 5_000,
            direction = "CREDIT", reason = "wallet.admin_credit", refType = "ride.completed",
            refId = "r1", createdAtEpochSec = day11_0001,
        )
        val s = EarningsAggregator.summarize(
            rides = listOf(ride("r1", method = "WALLET", fareMinor = 1_000, updatedAtSec = day11_0001)),
            history = listOf(foreign),
            nowEpochSec = day11_0001,
        )
        assertNull(s.trips.single().walletCreditMinor)
        assertEquals(0L, s.todayWalletMinor)
    }

    @Test
    fun tripsSortedNewestFirst_nonCompletedExcluded() {
        val s = EarningsAggregator.summarize(
            rides = listOf(
                ride("old", fareMinor = 100, updatedAtSec = day10_2359),
                ride("new", fareMinor = 200, updatedAtSec = day11_0001),
                ride("wip", status = "STARTED", fareMinor = 999, updatedAtSec = day11_0001),
                ride("req", status = "REQUESTED", fareMinor = 999, updatedAtSec = day11_0001),
            ),
            history = emptyList(),
            nowEpochSec = day11_0001,
        )
        assertEquals(listOf("new", "old"), s.trips.map { it.rideId })
        assertEquals(2, s.weekTrips)
    }

    @Test
    fun emptyInputs_emptySummary() {
        val s = EarningsAggregator.summarize(emptyList(), emptyList(), nowEpochSec = day11_0001)
        assertEquals(0, s.todayTrips)
        assertEquals(0, s.weekTrips)
        assertEquals(0L, s.weekCashMinor)
        assertEquals(0L, s.weekWalletMinor)
        assertEquals(0, s.trips.size)
    }

    @Test
    fun promoDiscount_chargeIsTheEarningsBasis() {
        // quote 1000, promo took 300 -> charge 700; the capture credit must match the
        // CHARGE basis (700 minus commission), not the signed quote.
        val ride = ride("r1", method = "WALLET", fareMinor = 1_000, updatedAtSec = day11_0001)
            .copy(chargeAmountMinor = 700L)
        val s = EarningsAggregator.summarize(
            rides = listOf(ride),
            history = listOf(capture("r1", amountMinor = 630, atSec = day11_0001)),
            nowEpochSec = day11_0001,
        )
        val t = s.trips.single()
        assertEquals(700L, t.fareMinor)
        assertEquals(630L, t.walletCreditMinor)
        assertEquals(70L, t.commissionMinor)
        assertEquals(630L, s.todayWalletMinor)
    }
}

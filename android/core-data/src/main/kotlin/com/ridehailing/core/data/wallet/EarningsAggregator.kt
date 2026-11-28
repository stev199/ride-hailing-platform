package com.ridehailing.core.data.wallet

import com.ridehailing.core.data.network.DriverRideDto
import com.ridehailing.core.data.network.WalletHistoryItemDto

/**
 * P7-4 driver earnings aggregation - pure client-side math over two existing
 * endpoints (GET /v1/driver/rides + GET /v1/wallet/transactions); the backend adds
 * no earnings endpoint in v1 and the aggregator invents no money it did not see:
 *
 * - CASH rides: completed CASH rides, fare = quote.amountMinor (collected physically,
 *   never moves the ledger).
 * - WALLET rides: the driver's capture credit is the ledger entry with
 *   reason="wallet.capture" + refType="ride.completed" + refId=<ride id>; the visible
 *   amount is what the driver actually got (fare minus commission). Commission is
 *   DERIVED (fare - credit) and clamped at 0 when config changed mid-flight; a wallet
 *   ride without a (yet) visible credit surfaces as pending, not as money.
 * - Day/week buckets use UTC days (sameUtcDay parity with the backend daily cap).
 */
object EarningsAggregator {

    const val REASON_CAPTURE: String = "wallet.capture"
    const val REFTYPE_COMPLETED: String = "ride.completed"

    data class TripEarning(
        val rideId: String,
        val city: String,
        val rideType: String,
        val paymentMethod: String,
        val fareMinor: Long,
        /** CREDIT actually seen for WALLET rides (null = pending/unseen; 0 for CASH). */
        val walletCreditMinor: Long?,
        /** Derived commission (fare - credit), >= 0; 0 for CASH. */
        val commissionMinor: Long,
        val completedAtEpochSec: Long,
    )

    data class Summary(
        val todayCashMinor: Long,
        val todayWalletMinor: Long,
        val todayTrips: Int,
        val weekCashMinor: Long,
        val weekWalletMinor: Long,
        val weekTrips: Int,
        val trips: List<TripEarning>,
    )

    /** Completed rides newest-first, split cash/wallet, day+week buckets (UTC). */
    fun summarize(
        rides: List<DriverRideDto>,
        history: List<WalletHistoryItemDto>,
        nowEpochSec: Long,
        weekWindowSec: Long = 7L * 24 * 60 * 60,
    ): Summary {
        val credits = history
            .filter { it.reason == REASON_CAPTURE && it.refType == REFTYPE_COMPLETED && it.direction == "CREDIT" }
            .associateBy { it.refId }

        val trips = rides
            .filter { it.status == "COMPLETED" }
            .sortedByDescending { it.updatedAtEpochSec }
            .map { ride ->
                // P7-6: the CHARGE (quote minus promo discount) is the money basis -
                // the credit capture used it; a legacy 0 value (older wire) falls
                // back to the signed quote.
                val fareBasis = ride.chargeAmountMinor.takeIf { it > 0 } ?: ride.quote.amountMinor
                val credit = if (ride.paymentMethod == "WALLET") credits[ride.id]?.amountMinor else null
                TripEarning(
                    rideId = ride.id,
                    city = ride.city,
                    rideType = ride.rideType,
                    paymentMethod = ride.paymentMethod,
                    fareMinor = fareBasis,
                    walletCreditMinor = credit,
                    commissionMinor = if (credit != null) maxOf(0L, fareBasis - credit) else 0L,
                    completedAtEpochSec = ride.updatedAtEpochSec,
                )
            }

        fun sameUtcDay(epochSec: Long, now: Long): Boolean {
            val dayOf = { s: Long -> s / 86_400L }
            return dayOf(epochSec) == dayOf(now)
        }

        val today = trips.filter { sameUtcDay(it.completedAtEpochSec, nowEpochSec) }
        val week = trips.filter { nowEpochSec - it.completedAtEpochSec <= weekWindowSec }

        fun cash(list: List<TripEarning>): Long = list.filter { it.paymentMethod == "CASH" }.sumOf { it.fareMinor }
        fun wallet(list: List<TripEarning>): Long = list.sumOf { it.walletCreditMinor ?: 0L }

        return Summary(
            todayCashMinor = cash(today),
            todayWalletMinor = wallet(today),
            todayTrips = today.size,
            weekCashMinor = cash(week),
            weekWalletMinor = wallet(week),
            weekTrips = week.size,
            trips = trips,
        )
    }
}

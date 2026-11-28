package com.ridehailing.core.data.network

import kotlinx.serialization.Serializable

/**
 * P7-3 wallet wire DTOs - byte-mirrors of the backend wallet contract (WalletRoutes.kt,
 * P7-1): GET /v1/wallet (balances + held), GET /v1/wallet/transactions (ledger history),
 * POST /v1/wallet/transfer (points transfer by phone). Unknown keys are ignored (D46)
 * so the backend can add fields ahead of the app release.
 */
@Serializable
data class WalletBalancesDto(
    /** Present currencies -> minor units (v1: POINTS; CASH accrues with P7-2). */
    val balances: Map<String, Long> = emptyMap(),
    /** Amount currently moved into the hold wallet (booking holds, P7-2). */
    val heldMinor: Long = 0,
)

@Serializable
data class WalletHistoryItemDto(
    val entryId: String,
    val transactionId: String,
    val amountMinor: Long,
    val direction: String,
    val reason: String,
    val refType: String? = null,
    val refId: String? = null,
    val createdAtEpochSec: Long,
)

@Serializable
data class WalletHistoryDto(
    val items: List<WalletHistoryItemDto> = emptyList(),
    val limit: Int,
    val offset: Int,
)

@Serializable
data class WalletTransferRequestDto(
    val toPhone: String,
    val amountMinor: Long,
    val note: String? = null,
)

@Serializable
data class WalletTransferResultDto(
    val feeMinor: Long,
    val receiverGotMinor: Long,
    val balanceMinor: Long,
)

/**
 * The wallet section of the device-visible signed config bundle (P7-1, D75 full-replace):
 * {transferFeeBps, minTransferMinor, dailyTransferLimitMinor}. commissionBps is
 * intentionally NOT in the device bundle (backend canonical JSON) - it is a platform-side
 * rate, not a rider-facing surface.
 */
@Serializable
data class DeviceWalletConfig(
    val transferFeeBps: Int = 0,
    val minTransferMinor: Long = 1L,
    val dailyTransferLimitMinor: Long = 100_000L,
)

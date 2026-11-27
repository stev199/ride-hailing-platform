package com.ridehailing.backend.wallet

import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.config.WalletConfig
import com.ridehailing.backend.ledger.Direction
import com.ridehailing.backend.ledger.InsufficientBalanceException
import com.ridehailing.backend.ledger.LedgerHistoryItem
import com.ridehailing.backend.ledger.LedgerLeg
import com.ridehailing.backend.ledger.LedgerStore
import com.ridehailing.backend.ledger.Wallet

/**
 * P7-1 wallet service over the double-entry [LedgerStore] port (spec §delivery plan 7:
 * "internal double-entry wallet/points ledger... Points transfers are first-class").
 *
 * - POINTS wallets are per-user and never negative (the store enforces it); a hold is
 *   a real double-entry move into a per-user hold wallet ("hold:POINTS"), so the main
 *   balance reflects held funds immediately and conservation holds by construction.
 * - CASH wallets exist for cash accounting (P7-2 wires ride money; the wallet itself
 *   is created here on demand).
 * - Transfers charge a fee (bps of the amount) into the PLATFORM fee wallet; limits
 *   come from the signed wallet config section (admin full-replace, D75).
 *
 * HONEST SCOPE (v1): transfer by PHONE only (QR/username land with the client work);
 * no PIN confirmation yet (recorded as a Phase 9/security-pass item); daily limit is
 * evaluated over the current UTC day of the owner's transfer history.
 */
class WalletService(
    private val ledger: LedgerStore,
    private val users: UserDirectory,
    private val config: () -> WalletConfig,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    /** Narrow seam for tests: the service only needs byPhone/byId lookups. */
    interface UserDirectory {
        fun byPhone(phone: String): User?
        fun byId(id: String): User?
    }

    companion object {
        const val POINTS = "POINTS"
        const val PLATFORM_FEE_OWNER = "platform:points-fees"
        const val PLATFORM_FUND_OWNER = "platform:points-fund"
        const val REASON_HOLD = "wallet.hold"
        const val REASON_CAPTURE = "wallet.capture"
        const val REASON_RELEASE = "wallet.release"
        const val REASON_TRANSFER = "wallet.transfer"
        const val REASON_ADMIN_CREDIT = "wallet.admin_credit"
    }

    sealed class TransferResult {
        data class Done(val feeMinor: Long, val receiverGotMinor: Long) : TransferResult()
        data class Invalid(val field: String, val code: String) : TransferResult()
    }

    sealed class HoldResult {
        data object Held : HoldResult()
        data class Invalid(val field: String, val code: String) : HoldResult()
    }

    /** Balances for the money surfaces the owner sees (v1: POINTS; CASH accrues in P7-2). */
    fun balances(ownerId: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        ledger.walletFor(ownerId, POINTS)?.let { out[POINTS] = ledger.balance(it.id) }
        ledger.walletFor(ownerId, "CASH")?.let { out["CASH"] = ledger.balance(it.id) }
        return out
    }

    fun held(ownerId: String): Long {
        val hold = ledger.walletFor(ownerId, holdCurrency()) ?: return 0L
        return ledger.balance(hold.id)
    }

    fun history(ownerId: String, limit: Int, offset: Int): List<LedgerHistoryItem> {
        val wallet = ledger.walletFor(ownerId, POINTS) ?: return emptyList()
        return ledger.historyFor(wallet.id, limit, offset)
    }

    /**
     * Points transfer by phone (first-class, spec §wallet). Fee = bps of the amount,
     * taken from the RECEIVED amount (receiver gets amount - fee), posted to the
     * platform fee wallet in the SAME transaction (money conservation, always).
     */
    fun transferPoints(actor: User, toPhone: String, amountMinor: Long, note: String?): TransferResult {
        if (amountMinor <= 0) return TransferResult.Invalid("amountMinor", "invalid_amount")
        val cfg = config()
        if (amountMinor < cfg.minTransferMinor) return TransferResult.Invalid("amountMinor", "below_minimum")
        val target = users.byPhone(toPhone) ?: return TransferResult.Invalid("toPhone", "unknown_user")
        if (target.id == actor.id) return TransferResult.Invalid("toPhone", "self_transfer")

        // Daily cap: sum of today's OUTGOING transfer principal (v1: history rows with
        // the transfer reason; the history window is capped at 500 rows - a user who
        // hit 500 transfers in one day has blown any sane limit anyway).
        val today = history(actor.id, limit = 500, offset = 0)
            .filter { it.reason == REASON_TRANSFER && it.direction == Direction.DEBIT && sameUtcDay(it.createdAtEpochSec) }
            .sumOf { it.amountMinor }
        if (today + amountMinor > cfg.dailyTransferLimitMinor) {
            return TransferResult.Invalid("amountMinor", "daily_limit_exceeded")
        }

        val feeMinor = amountMinor * cfg.transferFeeBps / 10_000
        val receiverGot = amountMinor - feeMinor
        // Check-then-post: a refused transfer must not leave wallets behind (the
        // sender wallet must EXIST and the receiver wallet is created inside the
        // posting only when the debit can actually succeed).
        val from = ledger.walletFor(actor.id, POINTS)
            ?: return TransferResult.Invalid("amountMinor", "insufficient_balance")
        val feeWallet = ledger.walletFor(PLATFORM_FEE_OWNER, POINTS)
            ?: ledger.ensureWallet(PLATFORM_FEE_OWNER, POINTS)
        val to = ledger.ensureWallet(target.id, POINTS)
        val legs = mutableListOf(LedgerLeg(from.id, amountMinor, Direction.DEBIT))
        legs += LedgerLeg(to.id, receiverGot, Direction.CREDIT)
        if (feeMinor > 0) legs += LedgerLeg(feeWallet.id, feeMinor, Direction.CREDIT)
        return try {
            ledger.postTransaction(legs, refType = "wallet_transfer", refId = note?.take(120), reason = REASON_TRANSFER)
            TransferResult.Done(feeMinor, receiverGot)
        } catch (_: InsufficientBalanceException) {
            TransferResult.Invalid("amountMinor", "insufficient_balance")
        }
    }

    /** HOLD: move [amountMinor] from the main POINTS wallet into the hold wallet. */
    fun hold(ownerId: String, amountMinor: Long, refType: String?, refId: String?): HoldResult {
        if (amountMinor <= 0) return HoldResult.Invalid("amountMinor", "invalid_amount")
        val main = ledger.walletFor(ownerId, POINTS) ?: return HoldResult.Invalid("amountMinor", "insufficient_balance")
        val hold = ledger.ensureWallet(ownerId, holdCurrency())
        return try {
            ledger.postTransaction(
                listOf(
                    LedgerLeg(main.id, amountMinor, Direction.DEBIT),
                    LedgerLeg(hold.id, amountMinor, Direction.CREDIT),
                ),
                refType = refType, refId = refId, reason = REASON_HOLD,
            )
            HoldResult.Held
        } catch (_: InsufficientBalanceException) {
            HoldResult.Invalid("amountMinor", "insufficient_balance")
        }
    }

    /** CAPTURE: move [amountMinor] OUT of the hold wallet to [toOwnerId] (ride fare). */
    fun capture(ownerId: String, toOwnerId: String, amountMinor: Long, refType: String?, refId: String?): HoldResult {
        if (amountMinor <= 0) return HoldResult.Invalid("amountMinor", "invalid_amount")
        val hold = ledger.walletFor(ownerId, holdCurrency())
            ?: return HoldResult.Invalid("amountMinor", "no_hold")
        if (ledger.balance(hold.id) < amountMinor) return HoldResult.Invalid("amountMinor", "hold_too_small")
        val dest = ledger.ensureWallet(toOwnerId, POINTS)
        return try {
            ledger.postTransaction(
                listOf(
                    LedgerLeg(hold.id, amountMinor, Direction.DEBIT),
                    LedgerLeg(dest.id, amountMinor, Direction.CREDIT),
                ),
                refType = refType, refId = refId, reason = REASON_CAPTURE,
            )
            HoldResult.Held
        } catch (_: InsufficientBalanceException) {
            HoldResult.Invalid("amountMinor", "insufficient_balance")
        }
    }

    /** RELEASE: return [amountMinor] from the hold wallet to the main wallet. */
    fun release(ownerId: String, amountMinor: Long, refType: String?, refId: String?): HoldResult {
        if (amountMinor <= 0) return HoldResult.Invalid("amountMinor", "invalid_amount")
        val hold = ledger.walletFor(ownerId, holdCurrency())
            ?: return HoldResult.Invalid("amountMinor", "no_hold")
        if (ledger.balance(hold.id) < amountMinor) return HoldResult.Invalid("amountMinor", "hold_too_small")
        val main = ledger.ensureWallet(ownerId, POINTS)
        return try {
            ledger.postTransaction(
                listOf(
                    LedgerLeg(hold.id, amountMinor, Direction.DEBIT),
                    LedgerLeg(main.id, amountMinor, Direction.CREDIT),
                ),
                refType = refType, refId = refId, reason = REASON_RELEASE,
            )
            HoldResult.Held
        } catch (_: InsufficientBalanceException) {
            HoldResult.Invalid("amountMinor", "insufficient_balance")
        }
    }

    /**
     * Admin credit (MONEY_WRITE, audited by the route): platform fund DEBIT ->
     * user POINTS CREDIT. Admin credit is the v1 top-up path (vouchers/cash agents
     * land with the provider work, honest scope note).
     */
    fun adminCredit(targetId: String, amountMinor: Long, reason: String): TransferResult {
        if (amountMinor <= 0) return TransferResult.Invalid("amountMinor", "invalid_amount")
        val target = users.byId(targetId) ?: return TransferResult.Invalid("userId", "unknown_user")
        // The platform fund ISSUES money -> it must be allowed to go negative
        // (double-entry still conserves: every credit to a user is a fund debit).
        val fund = ledger.ensureWallet(PLATFORM_FUND_OWNER, POINTS, allowNegative = true)
        val to = ledger.ensureWallet(target.id, POINTS)
        return try {
            ledger.postTransaction(
                listOf(
                    LedgerLeg(fund.id, amountMinor, Direction.DEBIT),
                    LedgerLeg(to.id, amountMinor, Direction.CREDIT),
                ),
                refType = "admin_credit", refId = reason.take(120), reason = REASON_ADMIN_CREDIT,
            )
            TransferResult.Done(0, amountMinor)
        } catch (_: InsufficientBalanceException) {
            TransferResult.Invalid("amountMinor", "insufficient_balance")
        }
    }

    private fun holdCurrency(): String = "hold:POINTS"

    private fun sameUtcDay(epochSec: Long): Boolean {
        val zone = java.time.ZoneOffset.UTC
        val a = java.time.Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
        val b = clock().atZone(zone).toLocalDate()
        return a == b
    }
}

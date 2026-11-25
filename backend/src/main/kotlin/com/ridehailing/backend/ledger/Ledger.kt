package com.ridehailing.backend.ledger

import java.util.UUID

/**
 * Double-entry ledger port (P1-5): schema + store + invariant tests now, routes/services
 * Phase 7. Semantics (pinned by LedgerStoreContractTest, run against BOTH the in-memory
 * store locally and the PG store in CI):
 * - A transaction is a set of legs; every leg is positive-amount in ONE wallet with a
 *   direction. DEBIT reduces the wallet balance, CREDIT increases it.
 * - A transaction is valid only when total DEBIT == total CREDIT (money conservation).
 * - Posting is ATOMIC: an invalid transaction leaves zero entries and zero balance moves.
 * - POINTS wallets can never go negative; CASH wallets may (allow_negative credit line).
 */
enum class Direction { DEBIT, CREDIT }

data class Wallet(
    val id: String,
    val ownerId: String,
    val currency: String,
    val balanceMinor: Long,
    val allowNegative: Boolean,
)

data class LedgerTransaction(
    val id: String,
    val refType: String?,
    val refId: String?,
    val reason: String,
    val createdAtEpochSec: Long,
)

data class LedgerEntry(
    val id: String,
    val transactionId: String,
    val walletId: String,
    val amountMinor: Long,
    val direction: Direction,
)

/** One intended per-wallet movement inside a transaction. */
data class LedgerLeg(val walletId: String, val amountMinor: Long, val direction: Direction)

/** Caller error: legs do not conserve value. Same type from every implementation. */
class UnbalancedLedgerTransactionException(message: String) : IllegalArgumentException(message)

/** Caller error: a non-negative wallet would go below zero. */
class InsufficientBalanceException(message: String) : IllegalStateException(message)

interface LedgerStore {
    fun ensureWallet(ownerId: String, currency: String = "POINTS", allowNegative: Boolean = false): Wallet

    fun walletFor(ownerId: String, currency: String = "POINTS"): Wallet?

    fun balance(walletId: String): Long

    /**
     * Atomically post one transaction. Throws [UnbalancedLedgerTransactionException] when
     * the legs do not sum to zero, [InsufficientBalanceException] when a non-negative
     * wallet would go below zero. On any throw, NOTHING is persisted.
     */
    fun postTransaction(legs: List<LedgerLeg>, refType: String?, refId: String?, reason: String): LedgerTransaction

    fun entriesFor(walletId: String, limit: Int = 50): List<LedgerEntry>
}

/** Validate + summarize legs; shared by every implementation. */
object LedgerRules {
    /**
     * Throws [UnbalancedLedgerTransactionException] unless every leg is positive, every
     * wallet exists and total DEBIT == total CREDIT. Returns the conserved amount.
     */
    fun validateLegs(legs: List<LedgerLeg>, walletsById: Map<String, Wallet>): Long {
        if (legs.isEmpty()) throw UnbalancedLedgerTransactionException("transaction needs at least one leg")
        var debits = 0L
        var credits = 0L
        for (leg in legs) {
            if (leg.amountMinor <= 0) {
                throw UnbalancedLedgerTransactionException("leg amounts must be positive, got ${leg.amountMinor}")
            }
            if (!walletsById.containsKey(leg.walletId)) {
                throw UnbalancedLedgerTransactionException("unknown wallet ${leg.walletId}")
            }
            when (leg.direction) {
                Direction.DEBIT -> debits += leg.amountMinor
                Direction.CREDIT -> credits += leg.amountMinor
            }
        }
        if (debits != credits) {
            throw UnbalancedLedgerTransactionException("unbalanced transaction: debit=$debits credit=$credits")
        }
        return debits
    }

    fun newId(): String = UUID.randomUUID().toString()
}

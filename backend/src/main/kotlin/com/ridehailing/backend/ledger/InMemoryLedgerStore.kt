package com.ridehailing.backend.ledger

/**
 * In-memory [LedgerStore] (P1-5): same port semantics as [com.ridehailing.backend.db.PgLedgerStore],
 * used for local/sandbox tests (no Docker in the sandbox - degraded-verification record)
 * and as the reference behavior the PG implementation must match.
 */
class InMemoryLedgerStore(private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 }) :
    LedgerStore {

    private val wallets = LinkedHashMap<String, Wallet>()
    private val transactions = LinkedHashMap<String, LedgerTransaction>()
    private val entries = LinkedHashMap<String, LedgerEntry>()

    override fun ensureWallet(ownerId: String, currency: String, allowNegative: Boolean): Wallet {
        val existing = wallets.values.firstOrNull { it.ownerId == ownerId && it.currency == currency }
        if (existing != null) return existing
        val wallet = Wallet(
            id = LedgerRules.newId(),
            ownerId = ownerId,
            currency = currency,
            balanceMinor = 0L,
            allowNegative = allowNegative,
        )
        wallets[wallet.id] = wallet
        return wallet
    }

    override fun walletFor(ownerId: String, currency: String): Wallet? =
        wallets.values.firstOrNull { it.ownerId == ownerId && it.currency == currency }

    override fun balance(walletId: String): Long =
        wallets[walletId]?.balanceMinor ?: throw UnbalancedLedgerTransactionException("unknown wallet $walletId")

    override fun postTransaction(
        legs: List<LedgerLeg>,
        refType: String?,
        refId: String?,
        reason: String,
    ): LedgerTransaction {
        val walletsById = wallets
        LedgerRules.validateLegs(legs, walletsById)

        // balance simulation first: any violation aborts the whole post (atomicity)
        val deltas = HashMap<String, Long>()
        for (leg in legs) {
            val wallet = walletsById.getValue(leg.walletId)
            val delta = when (leg.direction) {
                Direction.CREDIT -> leg.amountMinor
                Direction.DEBIT -> -leg.amountMinor
            }
            val next = (deltas[leg.walletId] ?: 0L) + delta
            deltas[leg.walletId] = next
            if (!wallet.allowNegative && wallet.balanceMinor + next < 0) {
                throw InsufficientBalanceException(
                    "wallet ${leg.walletId} would go to ${wallet.balanceMinor + next}",
                )
            }
        }

        val tx = LedgerTransaction(
            id = LedgerRules.newId(),
            refType = refType,
            refId = refId,
            reason = reason,
            createdAtEpochSec = nowEpochSec(),
        )
        transactions[tx.id] = tx
        for (leg in legs) {
            val entry = LedgerEntry(
                id = LedgerRules.newId(),
                transactionId = tx.id,
                walletId = leg.walletId,
                amountMinor = leg.amountMinor,
                direction = leg.direction,
            )
            entries[entry.id] = entry
        }
        for ((walletId, delta) in deltas) {
            val w = wallets.getValue(walletId)
            wallets[walletId] = w.copy(balanceMinor = w.balanceMinor + delta)
        }
        return tx
    }

    override fun entriesFor(walletId: String, limit: Int): List<LedgerEntry> =
        entries.values.filter { it.walletId == walletId }.takeLast(limit)
}

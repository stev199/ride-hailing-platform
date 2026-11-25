package com.ridehailing.backend.db

import com.ridehailing.backend.ledger.Direction
import com.ridehailing.backend.ledger.InsufficientBalanceException
import com.ridehailing.backend.ledger.LedgerEntry
import com.ridehailing.backend.ledger.LedgerLeg
import com.ridehailing.backend.ledger.LedgerRules
import com.ridehailing.backend.ledger.LedgerStore
import com.ridehailing.backend.ledger.LedgerTransaction
import com.ridehailing.backend.ledger.UnbalancedLedgerTransactionException
import com.ridehailing.backend.ledger.Wallet
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/**
 * Postgres-backed [LedgerStore] (P1-5). The double-entry invariant is enforced TWICE:
 * here (LedgerRules.validateLegs before any write) and at the DATABASE - the
 * DEFERRABLE constraint trigger from V4 rejects any commit whose transaction legs do
 * not sum DEBIT == CREDIT. Balance maintenance and entry writes happen in ONE SQL
 * transaction; the balance CHECK (allow_negative OR balance >= 0) guards points wallets.
 */
class PgLedgerStore(private val connect: () -> Connection) : LedgerStore {

    private fun ResultSet.toWallet(): Wallet = Wallet(
        id = getString("id"),
        ownerId = getString("owner_id"),
        currency = getString("currency"),
        balanceMinor = getLong("balance"),
        allowNegative = getBoolean("allow_negative"),
    )

    override fun ensureWallet(ownerId: String, currency: String, allowNegative: Boolean): Wallet =
        connect().use { c ->
            c.prepareStatement(
                "INSERT INTO wallet (owner_id, currency, balance, allow_negative) VALUES (?::uuid, ?, 0, ?) " +
                    "ON CONFLICT (owner_id, currency) DO UPDATE " +
                    "SET allow_negative = wallet.allow_negative OR EXCLUDED.allow_negative " +
                    "RETURNING id, owner_id, currency, balance, allow_negative",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, currency)
                ps.setBoolean(3, allowNegative)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "wallet upsert returned no row" }
                    rs.toWallet()
                }
            }
        }

    override fun walletFor(ownerId: String, currency: String): Wallet? = connect().use { c ->
        c.prepareStatement("SELECT id, owner_id, currency, balance, allow_negative FROM wallet WHERE owner_id = ? AND currency = ?")
            .use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, currency)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toWallet() else null }
            }
    }

    override fun balance(walletId: String): Long = connect().use { c ->
        c.prepareStatement("SELECT balance FROM wallet WHERE id = ?::uuid").use { ps ->
            ps.setString(1, walletId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1)
                else throw UnbalancedLedgerTransactionException("unknown wallet $walletId")
            }
        }
    }

    override fun postTransaction(
        legs: List<LedgerLeg>,
        refType: String?,
        refId: String?,
        reason: String,
    ): LedgerTransaction {
        // Pre-validate (also resolves wallets for balance checks) before touching the DB.
        val walletIds = legs.map { it.walletId }.distinct()
        val walletsById = walletIds.associateWith { id ->
            walletById(id) ?: throw UnbalancedLedgerTransactionException("unknown wallet $id")
        }
        LedgerRules.validateLegs(legs, walletsById)

        // Simulate balances so a points wallet going negative fails FAST with the
        // same exception type the in-memory store throws (and nothing is written).
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
                throw InsufficientBalanceException("wallet ${leg.walletId} would go to ${wallet.balanceMinor + next}")
            }
        }

        return connect().use { c ->
            c.autoCommit = false
            try {
                val txId: String
                c.prepareStatement(
                    "INSERT INTO ledger_transaction (ref_type, ref_id, reason) VALUES (?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, refType)
                    ps.setString(2, refId)
                    ps.setString(3, reason)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        txId = rs.getString(1)
                    }
                }
                for (leg in legs) {
                    c.prepareStatement(
                        "INSERT INTO ledger_entry (transaction_id, wallet_id, amount, direction) " +
                            "VALUES (?::uuid, ?::uuid, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, txId)
                        ps.setString(2, leg.walletId)
                        ps.setLong(3, leg.amountMinor)
                        ps.setString(4, leg.direction.name)
                        ps.executeUpdate()
                    }
                }
                for ((walletId, delta) in deltas) {
                    c.prepareStatement("UPDATE wallet SET balance = balance + ? WHERE id = ?::uuid").use { ps ->
                        ps.setLong(1, delta)
                        ps.setString(2, walletId)
                        check(ps.executeUpdate() == 1) { "balance update missed wallet $walletId" }
                    }
                }
                c.commit()
                LedgerTransaction(
                    id = txId,
                    refType = refType,
                    refId = refId,
                    reason = reason,
                    createdAtEpochSec = Instant.now().epochSecond,
                )
            } catch (e: SQLException) {
                runCatching { c.rollback() }
                // The constraint trigger (V4) is the LAST line of defense: an unbalanced
                // commit must never survive, and the caller sees a typed error.
                throw UnbalancedLedgerTransactionException("database rejected the transaction: ${e.message}")
            } finally {
                runCatching { c.autoCommit = true }
            }
        }
    }

    override fun entriesFor(walletId: String, limit: Int): List<LedgerEntry> = connect().use { c ->
        c.prepareStatement(
            "SELECT id, transaction_id, wallet_id, amount, direction FROM ledger_entry " +
                "WHERE wallet_id = ?::uuid ORDER BY created_at ASC, id ASC LIMIT ?",
        ).use { ps ->
            ps.setString(1, walletId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<LedgerEntry>()
                while (rs.next()) {
                    out.add(
                        LedgerEntry(
                            id = rs.getString("id"),
                            transactionId = rs.getString("transaction_id"),
                            walletId = rs.getString("wallet_id"),
                            amountMinor = rs.getLong("amount"),
                            direction = Direction.valueOf(rs.getString("direction")),
                        ),
                    )
                }
                out
            }
        }
    }

    private fun walletById(id: String): Wallet? = connect().use { c ->
        c.prepareStatement("SELECT id, owner_id, currency, balance, allow_negative FROM wallet WHERE id = ?::uuid")
            .use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toWallet() else null }
            }
    }

    companion object {
        /** Default connection factory used by tests/CI (same env as MigrationTest). */
        fun defaultConnect(): () -> Connection = {
            DriverManager.getConnection(
                System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing",
                System.getenv("POSTGRES_USER") ?: "ridehailing",
                System.getenv("POSTGRES_PASSWORD") ?: "test",
            )
        }
    }
}

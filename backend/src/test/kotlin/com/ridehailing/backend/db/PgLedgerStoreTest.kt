package com.ridehailing.backend.db

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.flywaydb.core.Flyway

/**
 * PG-specific ledger proof (P1-5): the V4 constraint trigger must reject an unbalanced
 * transaction AT COMMIT even when a writer bypasses the store code and posts raw SQL.
 * This is the database-level guarantee the double-entry ledger rests on. Skipped with
 * an explicit notice when no database is reachable (sandbox degraded mode, STATE.md).
 */
class PgLedgerStoreTest {
    private val url = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing"
    private val user = System.getenv("POSTGRES_USER") ?: "ridehailing"
    private val password = System.getenv("POSTGRES_PASSWORD") ?: "test"

    private fun dbAvailable(): Boolean = try {
        DriverManager.getConnection(url, user, password).use { it.isValid(3) }
    } catch (_: Exception) {
        false
    }

    @Test
    fun `database rejects an unbalanced raw-SQL transaction at commit`() {
        if (!dbAvailable()) {
            println("::notice::database not available - PgLedgerStoreTest skipped locally (CI runs it)")
            return
        }
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(url, user, password).use { c ->
            c.autoCommit = false
            // wallet.owner_id FKs to app_user(id): seed ONE real user and hang both
            // fixture wallets off it (the trigger proof only needs balanced legs).
            val ownerId = java.util.UUID.randomUUID().toString()
            c.prepareStatement(
                "INSERT INTO app_user (id, display_name, role, status, locale) " +
                    "VALUES (?::uuid, 'ledger-trigger-seed', 'RIDER', 'ACTIVE', 'en') " +
                    "ON CONFLICT (id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeUpdate()
            }
            val txId: String
            c.prepareStatement("INSERT INTO ledger_transaction (reason) VALUES ('trigger proof') RETURNING id")
                .use { ps -> ps.executeQuery().use { rs -> rs.next(); txId = rs.getString(1) } }
            val walletA = java.util.UUID.randomUUID().toString()
            val walletB = java.util.UUID.randomUUID().toString()
            c.prepareStatement(
                "INSERT INTO wallet (id, owner_id, currency, balance, allow_negative) VALUES (?::uuid, ?::uuid, 'POINTS', 0, FALSE)",
            ).use { ps ->
                listOf(walletA, walletB).forEach { id ->
                    ps.setString(1, id)
                    ps.setString(2, ownerId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.prepareStatement(
                "INSERT INTO ledger_entry (transaction_id, wallet_id, amount, direction) VALUES (?::uuid, ?::uuid, 100, 'DEBIT'), (?::uuid, ?::uuid, 90, 'CREDIT')",
            ).use { ps ->
                ps.setString(1, txId)
                ps.setString(2, walletA)
                ps.setString(3, txId)
                ps.setString(4, walletB)
                ps.executeUpdate()
            }

            try {
                c.commit()
                fail("unbalanced transaction must NOT commit - V4 constraint trigger missing")
            } catch (expected: java.sql.SQLException) {
                val msg = expected.message ?: ""
                assertTrue(
                    msg.contains("unbalanced", ignoreCase = true) || msg.contains("check_constraint_violation") || msg.contains("23514"),
                    "commit failure must be the balance invariant, was: $msg",
                )
            } finally {
                runCatching { c.rollback() }
                runCatching { c.autoCommit = true }
            }

            // after the failed commit: nothing persisted
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM ledger_entry WHERE transaction_id = '$txId'").use { rs ->
                    rs.next()
                    assertTrue(rs.getInt(1) == 0, "rollback removed the rejected legs")
                }
            }
        }
    }
}

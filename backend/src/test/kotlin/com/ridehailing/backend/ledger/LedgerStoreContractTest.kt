package com.ridehailing.backend.ledger

import com.ridehailing.backend.db.PgLedgerStore as PgStore
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

/**
 * Double-entry invariant CONTRACT tests (P1-5). Every scenario runs against the
 * in-memory store (local/sandbox) AND - when a Postgres is reachable (CI service
 * container) - against the PgLedgerStore, proving both implementations obey the same
 * port semantics. Unique owner ids per run keep the shared CI database collision-free.
 */
class LedgerStoreContractTest {

    private val pgUrl = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing"
    private val pgUser = System.getenv("POSTGRES_USER") ?: "ridehailing"
    private val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: "test"

    private fun pgAvailable(): Boolean = try {
        DriverManager.getConnection(pgUrl, pgUser, pgPassword).use { it.isValid(3) }
    } catch (_: Exception) {
        false
    }

    private fun stores(): List<Pair<String, LedgerStore>> {
        val list = mutableListOf<Pair<String, LedgerStore>>(
            "in-memory" to InMemoryLedgerStore(),
        )
        if (pgAvailable()) {
            // make sure V4 (constraint trigger) exists before the store is exercised
            Flyway.configure()
                .dataSource(pgUrl, pgUser, pgPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            list.add("postgres" to UserSeedingPgStore { DriverManager.getConnection(pgUrl, pgUser, pgPassword) })
        }
        return list
    }

    /**
     * wallet.owner_id carries a FK to app_user(id) (V1) - production wallets always
     * belong to real users. The contract scenarios generate owner ids freely, so the
     * PG test double seeds a minimal app_user row before every ensureWallet. Owner ids
     * are UUIDs so they can serve as app_user.id directly.
     */
    private class UserSeedingPgStore(private val connect: () -> java.sql.Connection) : LedgerStore {
        private val delegate = PgStore(connect)

        private fun seedUser(ownerId: String) {
            connect().use { c ->
                c.prepareStatement(
                    "INSERT INTO app_user (id, display_name, role, status, locale) " +
                        "VALUES (?::uuid, 'ledger-contract-seed', 'RIDER', 'ACTIVE', 'en') " +
                        "ON CONFLICT (id) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    ps.executeUpdate()
                }
            }
        }

        override fun ensureWallet(ownerId: String, currency: String, allowNegative: Boolean): Wallet {
            seedUser(ownerId)
            return delegate.ensureWallet(ownerId, currency, allowNegative)
        }

        override fun walletFor(ownerId: String, currency: String): Wallet? =
            delegate.walletFor(ownerId, currency)

        override fun balance(walletId: String): Long = delegate.balance(walletId)

        override fun postTransaction(
            legs: List<LedgerLeg>,
            refType: String?,
            refId: String?,
            reason: String,
        ): LedgerTransaction = delegate.postTransaction(legs, refType, refId, reason)

        override fun entriesFor(walletId: String, limit: Int): List<LedgerEntry> =
            delegate.entriesFor(walletId, limit)
    }

    private fun uid(prefix: String): String = java.util.UUID.randomUUID().toString()

    /** Seed a wallet by issuing value out of the treasury (treasury may go negative). */
    private fun seed(store: LedgerStore, wallet: Wallet, amount: Long) {
        val treasury = store.ensureWallet(uid("treasury"), wallet.currency, allowNegative = true)
        store.postTransaction(
            listOf(
                LedgerLeg(treasury.id, amount, Direction.DEBIT),
                LedgerLeg(wallet.id, amount, Direction.CREDIT),
            ),
            refType = "test.seed",
            refId = null,
            reason = "contract test seed",
        )
    }

    private fun freshPointsWallet(store: LedgerStore, seedAmount: Long): Wallet {
        val wallet = store.ensureWallet(uid("rider"), "POINTS")
        seed(store, wallet, seedAmount)
        return wallet
    }

    @Test
    fun `transfer moves value and conserves it`() {
        for ((name, store) in stores()) {
            val rider = freshPointsWallet(store, 500)
            val driver = store.ensureWallet(uid("driver"), "POINTS")

            val tx = store.postTransaction(
                listOf(
                    LedgerLeg(rider.id, 120, Direction.DEBIT),
                    LedgerLeg(driver.id, 120, Direction.CREDIT),
                ),
                refType = "ride.completed",
                refId = "ride-1",
                reason = "fare transfer",
            )

            assertEquals(380L, store.balance(rider.id), "$name: rider balance")
            assertEquals(120L, store.balance(driver.id), "$name: driver balance")
            assertEquals("ride.completed", tx.refType)
            assertTrue(store.entriesFor(rider.id, 50).any { it.transactionId == tx.id }, "$name: rider entry")
            assertTrue(store.entriesFor(driver.id, 50).any { it.transactionId == tx.id }, "$name: driver entry")
        }
    }

    @Test
    fun `unbalanced legs are rejected and nothing is written`() {
        for ((name, store) in stores()) {
            val rider = freshPointsWallet(store, 500)
            val driver = store.ensureWallet(uid("driver"), "POINTS")
            val riderEntriesBefore = store.entriesFor(rider.id, 100).size

            assertFailsWith<UnbalancedLedgerTransactionException>("$name: must refuse unbalanced") {
                store.postTransaction(
                    listOf(
                        LedgerLeg(rider.id, 100, Direction.DEBIT),
                        LedgerLeg(driver.id, 90, Direction.CREDIT),
                    ),
                    refType = null,
                    refId = null,
                    reason = "bad transfer",
                )
            }

            assertEquals(500L, store.balance(rider.id), "$name: rider untouched")
            assertEquals(0L, store.balance(driver.id), "$name: driver untouched")
            assertEquals(riderEntriesBefore, store.entriesFor(rider.id, 100).size, "$name: no entries written")
        }
    }

    @Test
    fun `multi-leg split with platform fee balances`() {
        for ((name, store) in stores()) {
            val rider = freshPointsWallet(store, 300)
            val driver = store.ensureWallet(uid("driver"), "POINTS")
            val platform = store.ensureWallet(uid("platform"), "POINTS")

            store.postTransaction(
                listOf(
                    LedgerLeg(rider.id, 110, Direction.DEBIT),
                    LedgerLeg(driver.id, 95, Direction.CREDIT),
                    LedgerLeg(platform.id, 15, Direction.CREDIT),
                ),
                refType = "ride.completed",
                refId = "ride-2",
                reason = "fare + commission",
            )

            assertEquals(190L, store.balance(rider.id), "$name: rider")
            assertEquals(95L, store.balance(driver.id), "$name: driver")
            assertEquals(15L, store.balance(platform.id), "$name: platform")
        }
    }

    @Test
    fun `points wallet cannot go negative - atomic rejection`() {
        for ((name, store) in stores()) {
            val rider = freshPointsWallet(store, 50)
            val treasury = store.ensureWallet(uid("treasury"), "POINTS", allowNegative = true)

            assertFailsWith<InsufficientBalanceException>("$name: must refuse overdraft") {
                store.postTransaction(
                    listOf(
                        LedgerLeg(rider.id, 60, Direction.DEBIT),
                        LedgerLeg(treasury.id, 60, Direction.CREDIT),
                    ),
                    refType = null,
                    refId = null,
                    reason = "overdraft attempt",
                )
            }
            assertEquals(50L, store.balance(rider.id), "$name: rider untouched")
            assertEquals(0L, store.balance(treasury.id), "$name: treasury untouched")
        }
    }

    @Test
    fun `cash wallet may go negative`() {
        for ((name, store) in stores()) {
            val cash = store.ensureWallet(uid("cash"), "CASH", allowNegative = true)
            val rider = freshPointsWallet(store, 200)

            store.postTransaction(
                listOf(
                    LedgerLeg(cash.id, 150, Direction.DEBIT),
                    LedgerLeg(rider.id, 150, Direction.CREDIT),
                ),
                refType = "cash.adjustment",
                refId = null,
                reason = "cash credit line",
            )

            assertEquals(-150L, store.balance(cash.id), "$name: cash went negative by design")
            assertEquals(350L, store.balance(rider.id), "$name: rider credited")
        }
    }

    @Test
    fun `unknown wallet and empty legs are rejected`() {
        for ((name, store) in stores()) {
            assertFailsWith<UnbalancedLedgerTransactionException>("$name: unknown wallet") {
                store.postTransaction(
                    listOf(LedgerLeg("does-not-exist", 10, Direction.CREDIT)),
                    refType = null,
                    refId = null,
                    reason = "bad",
                )
            }
            assertFailsWith<UnbalancedLedgerTransactionException>("$name: empty legs") {
                store.postTransaction(emptyList(), refType = null, refId = null, reason = "bad")
            }
            val w = freshPointsWallet(store, 10)
            assertFailsWith<UnbalancedLedgerTransactionException>("$name: zero/negative amount") {
                store.postTransaction(
                    listOf(
                        LedgerLeg(w.id, 0, Direction.DEBIT),
                        LedgerLeg(w.id, 0, Direction.CREDIT),
                    ),
                    refType = null,
                    refId = null,
                    reason = "bad",
                )
            }
        }
    }

    @Test
    fun `entriesFor returns the wallet history`() {
        for ((name, store) in stores()) {
            val wallet = freshPointsWallet(store, 100)
            val driver = store.ensureWallet(uid("driver"), "POINTS")

            store.postTransaction(
                listOf(
                    LedgerLeg(wallet.id, 40, Direction.DEBIT),
                    LedgerLeg(driver.id, 40, Direction.CREDIT),
                ),
                refType = null,
                refId = null,
                reason = "first",
            )
            store.postTransaction(
                listOf(
                    LedgerLeg(driver.id, 10, Direction.DEBIT),
                    LedgerLeg(wallet.id, 10, Direction.CREDIT),
                ),
                refType = null,
                refId = null,
                reason = "second",
            )

            val entries = store.entriesFor(wallet.id, 50)
            assertEquals(3, entries.size, "$name: seed + 2 transfers visible in history")
            assertEquals(
                listOf(Direction.CREDIT, Direction.DEBIT, Direction.CREDIT),
                entries.map { it.direction },
                "$name: chronological order",
            )
        }
    }
}

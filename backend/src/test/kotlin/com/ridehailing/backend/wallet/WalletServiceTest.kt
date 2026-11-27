package com.ridehailing.backend.wallet

import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.InMemoryUserStore
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.config.WalletConfig
import com.ridehailing.backend.ledger.Direction
import com.ridehailing.backend.ledger.InMemoryLedgerStore
import com.ridehailing.backend.ledger.LedgerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-1 wallet service scenarios against the IN-MEMORY ledger (the PG store runs the
 * same port contract in CI - LedgerStoreContractTest). Conservation is asserted on
 * every scenario: total DEBITS == total CREDITS across all wallets (closed system).
 */
class WalletServiceTest {

    private fun user(phone: String): User = User(
        id = Ids.newId(),
        phone = phone, email = null,
        passwordHash = "x",
        displayName = "U $phone", role = com.ridehailing.backend.auth.Role.RIDER,
        status = UserStatus.ACTIVE, locale = "en", createdAtEpochSec = 0,
    )

    private class Dir(private val users: List<User>) : WalletService.UserDirectory {
        override fun byPhone(phone: String): User? = users.firstOrNull { it.phone == phone }
        override fun byId(id: String): User? = users.firstOrNull { it.id == id }
    }

    private fun service(
        ledger: LedgerStore = InMemoryLedgerStore(nowEpochSec = { FIXED_NOW.epochSecond }),
        cfg: WalletConfig = WalletConfig(),
        users: List<User>,
    ): WalletService = WalletService(ledger, Dir(users), { cfg }, clock = { FIXED_NOW })

    private fun conservationHolds(ledger: LedgerStore): Boolean {
        // Sum every wallet balance; a closed system must stay at zero.
        val total = (ledger as InMemoryLedgerStore).snapshotBalances().values.sum()
        return total == 0L
    }

    @Test
    fun `transfer moves points, records history, conserves value`() {
        val ledger = InMemoryLedgerStore()
        val a = user("33110000001")
        val b = user("33110000002")
        val s = service(ledger, users = listOf(a, b))
        s.adminCredit(a.id, 1_000, "seed")
        assertEquals(1_000L, s.balances(a.id)[WalletService.POINTS])

        val res = s.transferPoints(a, "33110000002", 400, "thanks")
        assertTrue(res is WalletService.TransferResult.Done)
        assertEquals(0L, (res as WalletService.TransferResult.Done).feeMinor)
        assertEquals(400L, res.receiverGotMinor)
        assertEquals(600L, s.balances(a.id)[WalletService.POINTS])
        assertEquals(400L, s.balances(b.id)[WalletService.POINTS])
        assertTrue(conservationHolds(ledger), "closed system sums to zero")

        val hist = s.history(a.id, 50, 0)
        assertEquals(2, hist.size, "admin credit + transfer debit, newest first")
        assertEquals(WalletService.REASON_TRANSFER, hist[0].reason)
        assertEquals(Direction.DEBIT, hist[0].direction)
        assertEquals(400L, hist[0].amountMinor)
        assertEquals(WalletService.REASON_ADMIN_CREDIT, hist[1].reason)
    }

    @Test
    fun `transfer with fee splits receiver and platform inside one transaction`() {
        val ledger = InMemoryLedgerStore()
        val a = user("33110000011")
        val b = user("33110000012")
        val s = service(ledger, cfg = WalletConfig(transferFeeBps = 250, minTransferMinor = 1, dailyTransferLimitMinor = 100_000), users = listOf(a, b))
        s.adminCredit(a.id, 1_000, "seed")

        val res = s.transferPoints(a, "33110000012", 1_000, null)
        assertTrue(res is WalletService.TransferResult.Done)
        assertEquals(25L, (res as WalletService.TransferResult.Done).feeMinor)
        assertEquals(975L, res.receiverGotMinor)
        assertEquals(0L, s.balances(a.id)[WalletService.POINTS])
        assertEquals(975L, s.balances(b.id)[WalletService.POINTS])
        // Platform fee wallet holds exactly the fee.
        assertEquals(25L, s.balances(WalletService.PLATFORM_FEE_OWNER)[WalletService.POINTS])
        assertTrue(conservationHolds(ledger))
    }

    @Test
    fun `transfer rejects unknown phone, self, non-positive, below minimum`() {
        val a = user("33110000021")
        val b = user("33110000022")
        val s = service(cfg = WalletConfig(minTransferMinor = 50), users = listOf(a, b))
        s.adminCredit(a.id, 500, "seed")

        assertEquals("unknown_user", (s.transferPoints(a, "33999999999", 100, null) as WalletService.TransferResult.Invalid).code)
        assertEquals("self_transfer", (s.transferPoints(a, "33110000021", 100, null) as WalletService.TransferResult.Invalid).code)
        assertEquals("invalid_amount", (s.transferPoints(a, "33110000022", 0, null) as WalletService.TransferResult.Invalid).code)
        assertEquals("below_minimum", (s.transferPoints(a, "33110000022", 10, null) as WalletService.TransferResult.Invalid).code)
    }

    @Test
    fun `insufficient balance refuses without creating wallets`() {
        val a = user("33110000031")
        val b = user("33110000032")
        val s = service(users = listOf(a, b))
        assertEquals(
            "insufficient_balance",
            (s.transferPoints(a, "33110000032", 100, null) as WalletService.TransferResult.Invalid).code,
        )
        assertEquals(null, s.balances(a.id)[WalletService.POINTS], "refusal leaves no seeded wallet behind")
    }

    @Test
    fun `daily limit counts only today's outgoing transfers`() {
        val a = user("33110000041")
        val b = user("33110000042")
        val s = service(cfg = WalletConfig(dailyTransferLimitMinor = 300, minTransferMinor = 1), users = listOf(a, b))
        s.adminCredit(a.id, 1_000, "seed")
        assertTrue(s.transferPoints(a, "33110000042", 200, null) is WalletService.TransferResult.Done)
        assertEquals("daily_limit_exceeded", (s.transferPoints(a, "33110000042", 200, null) as WalletService.TransferResult.Invalid).code)
        assertTrue(s.transferPoints(a, "33110000042", 100, null) is WalletService.TransferResult.Done, "exactly up to the cap is allowed")
    }

    @Test
    fun `hold capture release - the booking money lifecycle`() {
        val ledger = InMemoryLedgerStore()
        val r = user("33110000051")
        val d = user("33110000052")
        val s = service(ledger, users = listOf(r, d))
        s.adminCredit(r.id, 800, "seed")

        assertTrue(s.hold(r.id, 500, "ride", "r1") is WalletService.HoldResult.Held)
        assertEquals(300L, s.balances(r.id)[WalletService.POINTS])
        assertEquals(500L, s.held(r.id))
        assertTrue(conservationHolds(ledger))

        assertEquals("insufficient_balance", (s.hold(r.id, 400, "ride", "r2") as WalletService.HoldResult.Invalid).code)
        assertEquals("hold_too_small", (s.capture(r.id, d.id, 600, "ride", "r1") as WalletService.HoldResult.Invalid).code)

        assertTrue(s.capture(r.id, d.id, 500, "ride", "r1") is WalletService.HoldResult.Held)
        assertEquals(500L, s.balances(d.id)[WalletService.POINTS])
        assertEquals(0L, s.held(r.id))
        assertEquals(300L, s.balances(r.id)[WalletService.POINTS])

        // Release path on a fresh hold.
        assertTrue(s.hold(r.id, 100, "ride", "r3") is WalletService.HoldResult.Held)
        assertTrue(s.release(r.id, 100, "ride", "r3") is WalletService.HoldResult.Held)
        assertEquals(300L, s.balances(r.id)[WalletService.POINTS])
        assertEquals(0L, s.held(r.id))
        assertTrue(conservationHolds(ledger))
    }

    @Test
    fun `admin credit history paginates newest-first with no overlap`() {
        val a = user("33110000061")
        val s = service(users = listOf(a))
        repeat(6) { s.adminCredit(a.id, (10 + it).toLong(), "seed $it") }
        assertEquals(75L, s.balances(a.id)[WalletService.POINTS], "10+11+12+13+14+15")

        val page1 = s.history(a.id, 2, 0)
        val page2 = s.history(a.id, 2, 2)
        assertEquals(2, page1.size)
        assertEquals(2, page2.size)
        assertTrue(page1[0].createdAtEpochSec >= page2[0].createdAtEpochSec, "newest first")
        assertTrue(page1.none { p1 -> page2.any { it.entryId == p1.entryId } }, "no overlap")
    }

    @Test
    fun `wallet config validation rejects impossible fee and limit combinations`() {
        assertTrue(WalletConfig(transferFeeBps = 1500).validationErrors().isNotEmpty())
        assertTrue(WalletConfig(minTransferMinor = 0).validationErrors().isNotEmpty())
        assertTrue(WalletConfig(minTransferMinor = 500, dailyTransferLimitMinor = 100).validationErrors().isNotEmpty())
        assertTrue(WalletConfig().validationErrors().isEmpty(), "defaults are valid")
    }

    companion object {
        private val FIXED_NOW: java.time.Instant = java.time.Instant.parse("2026-11-28T00:00:00Z")
    }
}

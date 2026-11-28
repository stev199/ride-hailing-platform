package com.ridehailing.backend.rides

import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.config.CancellationConfig
import com.ridehailing.backend.config.ConfigService
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.ledger.Direction
import com.ridehailing.backend.ledger.InMemoryLedgerStore
import com.ridehailing.backend.wallet.WalletService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-2 ride service: booking (signed quote), lazy expiry, rider cancel with the
 * ledger-posted cancel fee. Deterministic via the injected clock.
 * P8-1: free-window (inside = fee-free, outside = charged), post-accept cancels
 * (fee + WALLET hold release), reason catalogs (membership only when non-empty).
 */
class RideServiceTest {

    private val t0: Instant = Instant.parse("2026-11-25T12:00:00Z")

    private fun rider(id: String = Ids.newId(), role: Role = Role.RIDER) = User(
        id = id, phone = "+33610000000", email = null, passwordHash = null,
        displayName = "Test Rider", role = role, status = UserStatus.ACTIVE,
        locale = "en", createdAtEpochSec = 0,
    )

    private fun driver() = rider(role = Role.DRIVER)

    private fun service(clock: () -> Instant = { t0 }, events: MutableList<String> = mutableListOf()): RideService =
        RideService(
            store = InMemoryRideStore(),
            fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(),
            ledger = InMemoryLedgerStore(),
            osrm = null, // straight-line fallback (no geo stack locally)
            clock = clock,
            eventRecorder = { type, _ -> events.add(type) },
        )

    private val booking = RideService.CreateRequest(
        pickupLat = 43.7384, pickupLon = 7.4246,
        dropoffLat = 43.7543, dropoffLon = 7.4312,
        city = "monaco", rideType = "standard", paymentMethod = "CASH",
        note = "gate code 1234",
    )

    private fun accept(svc: RideService, driver: User, rideId: String) {
        svc.setDuty(driver, online = true, lat = 43.7384, lon = 7.4246)
        svc.accept(driver, rideId)
    }

    @Test
    fun `rider books - REQUESTED ride with a verifiable signed quote`() {
        val cfg = ConfigService(SignedConfigBundle.inMemoryDevKeys())
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = cfg.quoteSigningBundle, ledger = null, osrm = null, clock = { t0 },
        )
        val r = svc.create(rider(), booking)
        assertTrue(r is RideService.CreateResult.Created, "got $r")
        val ride = r.ride
        assertEquals(RideStatus.REQUESTED, ride.status)
        assertEquals("EUR", ride.quoteCurrency)
        assertTrue(ride.quoteAmountMinor > 0)
        assertEquals(t0.epochSecond + 90, ride.quoteValidUntilEpochSec, "default validity window 90s")
        assertEquals("upfront_locked", ride.quoteMode)
        // Signature round-trip against the bundle that signed it (device contract).
        assertTrue(RideQuote.verify(cfg.quoteSigningBundle, ride.quoteJson, ride.quoteSignature), "signature round-trip")
        // Tampered payload must NOT verify.
        assertTrue(!RideQuote.verify(cfg.quoteSigningBundle, ride.quoteJson.replace("amountMinor", "amountMinorX"), ride.quoteSignature))
        assertTrue(ride.quoteJson.contains("\"amountMinor\""), "canonical payload carries the amount")
        assertTrue(ride.quoteJson.contains("43.7384"), "canonical payload covers the pickup coordinates")
    }

    @Test
    fun `only riders can book`() {
        val svc = service()
        for (role in listOf(Role.DRIVER, Role.ADMIN_OPS)) {
            val r = svc.create(rider(role = role), booking)
            assertTrue(r is RideService.CreateResult.Forbidden, "$role booking must be 403, got $r")
            assertEquals("booking_role_required", r.code)
        }
    }

    @Test
    fun `unknown city, unknown ride type and non-cash payment are 400 codes`() {
        val svc = service()
        assertEquals("unknown_city", (svc.create(rider(), booking.copy(city = "paris")) as RideService.CreateResult.Invalid).code)
        assertEquals("unknown_ride_type", (svc.create(rider(), booking.copy(rideType = "helicopter")) as RideService.CreateResult.Invalid).code)
        assertEquals(
            "payment_method_unavailable",
            // WALLET is now valid (P7-2, upfront_locked); an UNKNOWN method still 400s.
            (svc.create(rider(), booking.copy(paymentMethod = "BITCOIN")) as RideService.CreateResult.Invalid).code,
        )
        assertEquals(
            "invalid_coordinates",
            (svc.create(rider(), booking.copy(pickupLat = 999.0)) as RideService.CreateResult.Invalid).code,
        )
    }

    @Test
    fun `requested ride past quote validity lazily expires on read`() {
        val events = mutableListOf<String>()
        var now = t0
        val svc = service(clock = { now }, events = events)
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        now = t0.plusSeconds(91)
        val read = svc.getForRider(ride.riderId, ride.id)!!
        assertEquals(RideStatus.EXPIRED, read.status, "lazy expiry on read")
        assertTrue("ride.expired" in events)
        // And the expired ride can no longer be cancelled (terminal).
        assertEquals(
            RideService.CancelResult.Error(409, "cancel_not_allowed"),
            svc.cancel(ride.riderId, ride.id),
        )
    }

    @Test
    fun `rider cancel INSIDE the free window is fee-free, the window edge is inclusive - P8-1`() {
        val ledger = InMemoryLedgerStore()
        val events = mutableListOf<Pair<String, Map<String, String?>>>()
        var now = t0
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null, clock = { now },
            eventRecorder = { type, payload -> events.add(type to payload) },
        )
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        // Cancel the SAME second the ride was booked: inside the default 60s window.
        val r = svc.cancel(ride.riderId, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled, "got $r")
        assertEquals(0L, r.ride.cancelFeeMinor, "inside the window the cancel is free")
        assertEquals(null, ledger.walletFor(ride.riderId, "EUR"), "no ledger movement inside the window")
        val ev = events.last { it.first == "ride.cancelled" }.second
        assertEquals("true", ev["freeWindowApplied"], "the event records the free-window decision")
        assertEquals("not_applicable", ev["walletRelease"], "REQUESTED cancel has no hold to release")
        // elapsed == window stays free; elapsed > window charges.
        val ride2 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        now = t0.plusSeconds(60)
        val atEdge = svc.cancel(ride2.riderId, ride2.id)
        assertEquals(0L, (atEdge as RideService.CancelResult.Cancelled).ride.cancelFeeMinor, "elapsed == 60s window is still free")
        val ride3 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        now = t0.plusSeconds(121)
        val past = svc.cancel(ride3.riderId, ride3.id)
        assertEquals(300L, (past as RideService.CancelResult.Cancelled).ride.cancelFeeMinor, "elapsed 61s > 60s window charges")
        assertEquals("false", events.last { it.first == "ride.cancelled" }.second["freeWindowApplied"])
    }

    @Test
    fun `rider cancel OUTSIDE the free window posts the configured cancel fee to the double-entry ledger`() {
        val events = mutableListOf<String>()
        val ledger = InMemoryLedgerStore()
        var now = t0
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null, clock = { now },
            eventRecorder = { type, _ -> events.add(type) },
        )
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        val riderWalletBalanceBefore = ledger.walletFor(ride.riderId, "EUR")?.balanceMinor
        now = t0.plusSeconds(61) // P8-1: step past the default 60s free window

        val r = svc.cancel(ride.riderId, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled, "got $r")
        assertEquals(RideStatus.CANCELLED_BY_RIDER, r.ride.status)
        assertEquals(300L, r.ride.cancelFeeMinor, "cancelFee 3.0 EUR = 300 minor units")
        assertTrue("ride.cancelled" in events)

        // Double-entry: rider DEBIT 300 <-> platform CREDIT 300.
        val riderWallet = ledger.walletFor(ride.riderId, "EUR")!!
        val platformWallet = ledger.walletFor(RideService.PLATFORM_ACCOUNT, "EUR")!!
        val riderDelta = riderWallet.balanceMinor - (riderWalletBalanceBefore ?: 0L)
        assertEquals(-300L, riderDelta, "rider cash wallet debited by the fee (CASH may go negative)")
        assertEquals(300L, platformWallet.balanceMinor, "platform credited by the fee")
        val entries = ledger.entriesFor(riderWallet.id)
        assertEquals(1, entries.size)
        assertEquals(Direction.DEBIT, entries.single().direction)
    }

    @Test
    fun `cancel with zero configured fee posts nothing to the ledger`() {
        val ledger = InMemoryLedgerStore()
        val zeroFeeFares = com.ridehailing.backend.config.defaultFares().let { cfg ->
            cfg.copy(cities = cfg.cities.mapValues { (_, c) ->
                c.copy(rideTypes = c.rideTypes.mapValues { (_, f) -> f.copy(cancelFee = 0.0) })
            })
        }
        var now = t0
        val svc = RideService(
            store = InMemoryRideStore(), fares = { zeroFeeFares },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null, clock = { now },
        )
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        now = t0.plusSeconds(61) // outside the free window, but the CONFIGURED fee is 0
        val r = svc.cancel(ride.riderId, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled)
        assertEquals(0L, r.ride.cancelFeeMinor)
        assertEquals(null, ledger.walletFor(ride.riderId, "EUR"), "no wallet touched when the fee is 0")
    }

    @Test
    fun `cancel is rider-owned - no existence leak for other riders`() {
        val svc = service()
        val riderA = rider()
        val riderB = rider()
        val ride = (svc.create(riderA, booking) as RideService.CreateResult.Created).ride
        assertEquals(RideService.CancelResult.Error(404, "ride_not_found"), svc.cancel(riderB.id, ride.id))
        assertTrue(svc.getForRider(riderB.id, ride.id) == null, "no existence leak")
        assertTrue(svc.getForRider(riderA.id, ride.id) != null)
    }

    // ---------- P8-1: post-accept rider cancel (fee + WALLET hold release) ----------

    /** Real wallet over the SAME ledger the ride service posts the fee to (P7-2 lifecycle). */
    private class MoneyGraph(
        val svc: RideService,
        val ledger: InMemoryLedgerStore,
        val wallet: WalletService,
        val rider: User,
        val driver: User,
    )

    private fun moneyGraph(
        clock: () -> Instant,
        events: MutableList<Pair<String, Map<String, String?>>> = mutableListOf(),
        cancellation: CancellationConfig = CancellationConfig(),
    ): MoneyGraph {
        val ledger = InMemoryLedgerStore()
        val riderUser = rider()
        val driverUser = driver()
        val wallet = WalletService(
            ledger = ledger,
            users = object : WalletService.UserDirectory {
                override fun byPhone(phone: String): User? = null
                override fun byId(id: String): User? = listOf(riderUser, driverUser).firstOrNull { it.id == id }
            },
            config = { com.ridehailing.backend.config.WalletConfig() },
        )
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null,
            walletProvider = { wallet },
            cancellationConfigProvider = { cancellation },
            clock = clock,
            eventRecorder = { type, payload -> events.add(type to payload) },
        )
        return MoneyGraph(svc, ledger, wallet, riderUser, driverUser)
    }

    @Test
    fun `post-accept rider cancel - fee ALWAYS charged, hold released, event records both - P8-1`() {
        val events = mutableListOf<Pair<String, Map<String, String?>>>()
        var now = t0
        val g = moneyGraph(clock = { now }, events = events)
        g.wallet.adminCredit(g.rider.id, 10_000, "seed")
        val ride = (g.svc.create(g.rider, booking.copy(paymentMethod = "WALLET")) as RideService.CreateResult.Created).ride
        accept(g.svc, g.driver, ride.id)
        now = t0.plusSeconds(5) // post-accept: the free window NO LONGER applies
        val r = g.svc.cancel(g.rider.id, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled, "got $r")
        assertEquals(RideStatus.CANCELLED_BY_RIDER, r.ride.status)
        assertEquals(300L, r.ride.cancelFeeMinor, "post-accept rider cancel always pays the fee")
        val ev = events.last { it.first == "ride.cancelled" }.second
        assertEquals("false", ev["freeWindowApplied"], "no free window after accept")
        assertEquals("released", ev["walletRelease"], "the pending hold is released before the fee")
    }

    @Test
    fun `post-accept rider cancel on a WALLET ride - charge returns, fee posts, conservation holds`() {
        var now = t0
        val g = moneyGraph(clock = { now })
        g.wallet.adminCredit(g.rider.id, 10_000, "seed")
        val ride = (g.svc.create(g.rider, booking.copy(paymentMethod = "WALLET")) as RideService.CreateResult.Created).ride
        val charge = ride.chargeAmountMinor
        accept(g.svc, g.driver, ride.id)
        assertEquals(charge, g.wallet.held(g.rider.id), "hold at accept")
        now = t0.plusSeconds(5)
        val r = g.svc.cancel(g.rider.id, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled, "got $r")
        // P7-2 honest semantics: the hold covered the CHARGE (quote minus discount) and
        // comes back whole; the 300-fee is separate and rides the CASH/EUR ledger.
        assertEquals(0L, g.wallet.held(g.rider.id), "hold fully released")
        assertEquals(10_000L, g.wallet.balances(g.rider.id)[WalletService.POINTS], "charge released back to POINTS")
        assertEquals(-300L, g.ledger.walletFor(g.rider.id, "EUR")?.balanceMinor ?: 0L, "fee debited from the rider's EUR wallet")
        assertEquals(300L, g.ledger.walletFor(RideService.PLATFORM_ACCOUNT, "EUR")?.balanceMinor ?: 0L, "platform credited the fee")
        assertEquals(0L, g.ledger.snapshotBalances().values.sum(), "double-entry conservation")
    }

    @Test
    fun `rider cancel of an IN_PROGRESS ride is 409 - P8-1`() {
        val svc = service()
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        val d = driver()
        accept(svc, d, ride.id)
        svc.driverAction(d, ride.id, RideStatus.ARRIVED)
        svc.driverAction(d, ride.id, RideStatus.IN_PROGRESS)
        assertEquals(
            RideService.CancelResult.Error(409, "cancel_not_allowed"),
            svc.cancel(ride.riderId, ride.id),
        )
    }

    @Test
    fun `rider reason out-of-catalog is 400, member accepted, free text when catalog empty - P8-1`() {
        val events = mutableListOf<Pair<String, Map<String, String?>>>()
        var now = t0
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = InMemoryLedgerStore(), osrm = null, clock = { now },
            cancellationConfigProvider = { CancellationConfig(riderReasons = listOf("changed_plans", "booked_wrong_city")) },
            eventRecorder = { type, payload -> events.add(type to payload) },
        )
        val r1 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertEquals(
            RideService.CancelResult.Error(400, "cancel_reason_not_allowed"),
            svc.cancel(r1.riderId, r1.id, "i just felt like it"),
            "non-empty catalog enforces membership",
        )
        val ok = svc.cancel(r1.riderId, r1.id, "  changed_plans  ")
        assertTrue(ok is RideService.CancelResult.Cancelled, "got $ok")
        assertEquals("changed_plans", events.last { it.first == "ride.cancelled" }.second["reason"], "reason trimmed into the event")

        // A blank reason folds to absent: the catalog constrains PROVIDED reasons only.
        val r2 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertTrue(svc.cancel(r2.riderId, r2.id, "   ") is RideService.CancelResult.Cancelled, "blank folds to absent")
        val r3 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertTrue(svc.cancel(r3.riderId, r3.id) is RideService.CancelResult.Cancelled, "absent reason is fine for riders")

        // Empty catalog = free text accepted (P8-1 decision).
        val free = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = null, osrm = null, clock = { now },
            eventRecorder = { type, payload -> events.add(type to payload) },
        )
        val r4 = (free.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertTrue(free.cancel(r4.riderId, r4.id, "anything at all") is RideService.CancelResult.Cancelled, "empty catalog accepts free text")

        // The 200-char per-request cap applies either way (openapi CancelRequest.maxLength).
        val r5 = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertEquals(
            RideService.CancelResult.Error(400, "cancel_reason_too_long"),
            svc.cancel(r5.riderId, r5.id, "x".repeat(201)),
            "too_long precedes the catalog check",
        )
        val r6 = (free.create(rider(), booking) as RideService.CreateResult.Created).ride
        assertTrue(free.cancel(r6.riderId, r6.id, "x".repeat(200)) is RideService.CancelResult.Cancelled, "200 chars is the documented cap")
    }

    @Test
    fun `ride list is rider-scoped newest-first`() {
        val svc = service()
        val riderA = rider()
        val riderB = rider()
        val r1 = (svc.create(riderA, booking) as RideService.CreateResult.Created).ride
        val r2 = (svc.create(riderA, booking) as RideService.CreateResult.Created).ride
        svc.create(riderB, booking)
        val list = svc.listForRider(riderA.id, 20, 0)
        assertEquals(listOf(r2.id, r1.id), list.map { it.id }, "newest first, only own rides")
    }
}

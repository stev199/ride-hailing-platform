package com.ridehailing.backend.rides

import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.config.ConfigService
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.ledger.Direction
import com.ridehailing.backend.ledger.InMemoryLedgerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-2 ride service: booking (signed quote), lazy expiry, rider cancel with the
 * ledger-posted cancel fee. Deterministic via the injected clock.
 */
class RideServiceTest {

    private val t0: Instant = Instant.parse("2026-11-25T12:00:00Z")

    private fun rider(id: String = Ids.newId(), role: Role = Role.RIDER) = User(
        id = id, phone = "+33610000000", email = null, passwordHash = null,
        displayName = "Test Rider", role = role, status = UserStatus.ACTIVE,
        locale = "en", createdAtEpochSec = 0,
    )

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
    fun `rider cancel posts the configured cancel fee to the double-entry ledger`() {
        val events = mutableListOf<String>()
        val ledger = InMemoryLedgerStore()
        val svc = RideService(
            store = InMemoryRideStore(), fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null, clock = { t0 },
            eventRecorder = { type, _ -> events.add(type) },
        )
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        val riderWalletBalanceBefore = ledger.walletFor(ride.riderId, "EUR")?.balanceMinor

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
        val svc = RideService(
            store = InMemoryRideStore(), fares = { zeroFeeFares },
            bundle = SignedConfigBundle.inMemoryDevKeys(), ledger = ledger, osrm = null, clock = { t0 },
        )
        val ride = (svc.create(rider(), booking) as RideService.CreateResult.Created).ride
        val r = svc.cancel(ride.riderId, ride.id)
        assertTrue(r is RideService.CancelResult.Cancelled)
        assertEquals(0L, r.ride.cancelFeeMinor)
        assertEquals(null, ledger.walletFor(ride.riderId, "EUR"), "no wallet touched when the fee is 0")
    }

    @Test
    fun `cancel is rider-owned and REQUESTED-only`() {
        val svc = service()
        val riderA = rider()
        val riderB = rider()
        val ride = (svc.create(riderA, booking) as RideService.CreateResult.Created).ride
        assertEquals(RideService.CancelResult.Error(404, "ride_not_found"), svc.cancel(riderB.id, ride.id))
        assertTrue(svc.getForRider(riderB.id, ride.id) == null, "no existence leak")
        assertTrue(svc.getForRider(riderA.id, ride.id) != null)
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

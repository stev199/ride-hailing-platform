package com.ridehailing.backend.rides

import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.ledger.InMemoryLedgerStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P5 driver dispatch service tests: duty registry, closest-first availability, the
 * ATOMIC single-winner claim, driver-owned trip transitions and ownership guards.
 * Deterministic via the injected clock (same harness as RideServiceTest).
 */
class DriverDispatchTest {

    private val t0: Instant = Instant.parse("2026-11-25T12:00:00Z")

    private fun user(id: String = Ids.newId(), role: Role) = User(
        id = id, phone = "+33610" + (1000000 + (id.hashCode() % 8999999)).toString(),
        email = null, passwordHash = null,
        displayName = "Test User", role = role, status = UserStatus.ACTIVE,
        locale = "en", createdAtEpochSec = 0,
    )

    private fun service(clock: () -> Instant = { t0 }, events: MutableList<String> = mutableListOf()): RideService =
        RideService(
            store = InMemoryRideStore(),
            fares = { com.ridehailing.backend.config.defaultFares() },
            bundle = SignedConfigBundle.inMemoryDevKeys(),
            ledger = InMemoryLedgerStore(),
            osrm = null,
            clock = clock,
            eventRecorder = { type, _ -> events.add(type) },
        )

    private val booking = RideService.CreateRequest(
        pickupLat = 43.7384, pickupLon = 7.4246,
        dropoffLat = 43.7543, dropoffLon = 7.4312,
        city = "monaco", rideType = "standard", paymentMethod = "CASH",
    )

    private fun book(svc: RideService, rider: User = user(role = Role.RIDER), pickupLat: Double = booking.pickupLat, pickupLon: Double = booking.pickupLon): Ride =
        (svc.create(rider, booking.copy(pickupLat = pickupLat, pickupLon = pickupLon)) as RideService.CreateResult.Created).ride

    private fun goOnline(svc: RideService, driver: User, lat: Double = 43.7384, lon: Double = 7.4246) {
        val r = svc.setDuty(driver, online = true, lat = lat, lon = lon)
        assertTrue(r is RideService.DutyResult.Set, "got $r")
    }

    private fun acceptOk(svc: RideService, driver: User, ride: Ride): Ride =
        (svc.accept(driver, ride.id) as RideService.DriverActionResult.Ok).ride

    @Test
    fun `duty is driver-only and validates coordinates`() {
        val events = mutableListOf<String>()
        val svc = service(events = events)
        assertEquals(
            RideService.DutyResult.Error(403, "driver_role_required"),
            svc.setDuty(user(role = Role.RIDER), online = true, lat = 43.7, lon = 7.4),
        )
        assertEquals(
            RideService.DutyResult.Error(400, "invalid_coordinates"),
            svc.setDuty(user(role = Role.DRIVER), online = true, lat = 91.0, lon = 7.4),
        )
        val driver = user(role = Role.DRIVER)
        val r = svc.setDuty(driver, online = true, lat = 43.7384, lon = 7.4246) as RideService.DutyResult.Set
        assertEquals(true, r.state.online)
        assertEquals(r.state, svc.dutyOf(driver.id))
        assertTrue("driver.duty" in events)
        // Going OFFLINE removes the registry entry (board poll then 409s).
        svc.setDuty(driver, online = false, lat = 43.7384, lon = 7.4246)
        assertTrue(svc.dutyOf(driver.id) == null)
    }

    @Test
    fun `available board is duty-gated, REQUESTED-only and closest-first`() {
        val svc = service()
        val driver = user(role = Role.DRIVER)
        assertEquals(
            RideService.AvailableResult.Error(409, "duty_offline"),
            svc.availableRides(driver),
        )
        // Two rides: one AT the driver, one deliberately far (upper-left of the extract).
        val near = book(svc)
        val farRide = book(svc, pickupLat = 43.7280, pickupLon = 7.4180)
        goOnline(svc, driver)
        val r = svc.availableRides(driver) as RideService.AvailableResult.Ok
        assertEquals(
            listOf(near.id, farRide.id).toSet(),
            r.rides.map { it.ride.id }.toSet(),
            "both REQUESTED rides visible",
        )
        assertEquals(near.id, r.rides.first().ride.id, "closest pickup first")
        assertTrue(r.rides.first().distanceMeters < r.rides.last().distanceMeters)
        // A rider cancels one -> it leaves the board immediately.
        svc.cancel(near.riderId, near.id)
        val after = svc.availableRides(driver) as RideService.AvailableResult.Ok
        assertEquals(listOf(farRide.id), after.rides.map { it.ride.id })
        // Going offline closes the board too.
        svc.setDuty(driver, online = false, lat = 43.7384, lon = 7.4246)
        assertEquals(
            RideService.AvailableResult.Error(409, "duty_offline"),
            svc.availableRides(driver),
        )
    }

    @Test
    fun `accept is an atomic single-winner claim`() {
        val events = mutableListOf<String>()
        val svc = service(events = events)
        val ride = book(svc)
        val winner = user(role = Role.DRIVER)
        val loser = user(role = Role.DRIVER)
        goOnline(svc, winner)
        goOnline(svc, loser)

        val a = acceptOk(svc, winner, ride)
        assertEquals(RideStatus.ACCEPTED, a.status)
        assertEquals(winner.id, a.driverId)
        assertEquals(
            RideService.DriverActionResult.Error(409, "already_accepted"),
            svc.accept(loser, ride.id),
        )
        assertTrue("ride.accepted" in events)
        // The loser cannot move the winner's ride (403, not 404 - it IS on the board).
        assertEquals(
            RideService.DriverActionResult.Error(403, "not_your_ride"),
            svc.driverAction(loser, ride.id, RideStatus.ARRIVED),
        )
    }

    @Test
    fun `trip transitions are driver-owned and machine-enforced`() {
        val events = mutableListOf<String>()
        val svc = service(events = events)
        val ride = book(svc)
        val driver = user(role = Role.DRIVER)
        goOnline(svc, driver)
        acceptOk(svc, driver, ride)

        // Another driver cannot touch it.
        val stranger = user(role = Role.DRIVER)
        assertEquals(
            RideService.DriverActionResult.Error(403, "not_your_ride"),
            svc.driverAction(stranger, ride.id, RideStatus.ARRIVED),
        )
        // Illegal transition: start before arrive.
        assertEquals(
            RideService.DriverActionResult.Error(409, "illegal_transition"),
            svc.driverAction(driver, ride.id, RideStatus.IN_PROGRESS),
        )
        // Happy path ACCEPTED -> ARRIVED -> IN_PROGRESS -> COMPLETED.
        assertEquals(RideStatus.ARRIVED, (svc.driverAction(driver, ride.id, RideStatus.ARRIVED) as RideService.DriverActionResult.Ok).ride.status)
        assertEquals(RideStatus.IN_PROGRESS, (svc.driverAction(driver, ride.id, RideStatus.IN_PROGRESS) as RideService.DriverActionResult.Ok).ride.status)
        assertEquals(RideStatus.COMPLETED, (svc.driverAction(driver, ride.id, RideStatus.COMPLETED) as RideService.DriverActionResult.Ok).ride.status)
        assertTrue("ride.completed" in events)
        // Terminal: no further move (complete again -> 409).
        assertEquals(
            RideService.DriverActionResult.Error(409, "illegal_transition"),
            svc.driverAction(driver, ride.id, RideStatus.COMPLETED),
        )
        // Unknown ride -> 404 (no leak).
        assertEquals(
            RideService.DriverActionResult.Error(404, "ride_not_found"),
            svc.driverAction(driver, "no-such-ride", RideStatus.ARRIVED),
        )
    }

    @Test
    fun `unassigned rides are 404 for actions (no existence leak)`() {
        val svc = service()
        val ride = book(svc)
        val driver = user(role = Role.DRIVER)
        goOnline(svc, driver)
        assertEquals(
            RideService.DriverActionResult.Error(404, "ride_not_found"),
            svc.driverAction(driver, ride.id, RideStatus.ARRIVED),
        )
    }

    @Test
    fun `rider cancel is refused once a driver accepted`() {
        val svc = service()
        val ride = book(svc)
        val driver = user(role = Role.DRIVER)
        goOnline(svc, driver)
        acceptOk(svc, driver, ride)
        assertEquals(
            RideService.CancelResult.Error(409, "cancel_not_allowed"),
            svc.cancel(ride.riderId, ride.id),
        )
    }

    @Test
    fun `accepting a ride past its quote validity lazily expires it`() {
        val events = mutableListOf<String>()
        var now = t0
        val svc = service(clock = { now }, events = events)
        val ride = book(svc)
        val driver = user(role = Role.DRIVER)
        goOnline(svc, driver)
        now = t0.plusSeconds(91)
        assertEquals(
            RideService.DriverActionResult.Error(409, "ride_expired"),
            svc.accept(driver, ride.id),
        )
        assertTrue("ride.expired" in events)
        // The rider's own read confirms the lazy terminal transition.
        val expired = svc.getForRider(ride.riderId, ride.id)!!
        assertEquals(RideStatus.EXPIRED, expired.status)
    }

    @Test
    fun `driver history lists only own rides newest-first`() {
        val svc = service()
        val driver = user(role = Role.DRIVER)
        goOnline(svc, driver)
        val r1 = book(svc)
        val r2 = book(svc, rider = user(role = Role.RIDER))
        acceptOk(svc, driver, r1)
        acceptOk(svc, driver, r2)
        val mine = svc.forDriver(driver.id, 20, 0)
        assertEquals(listOf(r2.id, r1.id), mine.map { it.id }, "assigned rides only, newest first")
        assertTrue(mine.all { it.driverId == driver.id })
        // A stranger's driver history stays empty.
        assertTrue(svc.forDriver(user(role = Role.DRIVER).id, 20, 0).isEmpty())
    }

    @Test
    fun `role guards - rider cannot accept or act`() {
        val svc = service()
        val ride = book(svc)
        val rider = user(role = Role.RIDER)
        assertEquals(
            RideService.DriverActionResult.Error(403, "driver_role_required"),
            svc.accept(rider, ride.id),
        )
        assertEquals(
            RideService.DriverActionResult.Error(403, "driver_role_required"),
            svc.driverAction(rider, ride.id, RideStatus.ARRIVED),
        )
    }
}

package com.ridehailing.backend.fares

import com.ridehailing.backend.config.defaultFares
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-1 fare service orchestration: route source selection (OSRM vs degraded
 * straight-line fallback), ride-type expansion/ordering, unknown city/ride-type
 * rejection. The NUMBERS are pinned by FareMathKatTest; this suite pins the
 * INPUT wiring.
 */
class FareServiceTest {

    private val fares = defaultFares()

    private val stubRoute = OsrmClient.Route(distanceMeters = 5234.5, durationSeconds = 720.0)
    private val stubOsrm = OsrmRouteSource { _, _, _, _ -> stubRoute }
    private val fixedHour = java.time.Instant.parse("2026-11-25T12:10:00Z") // noon UTC: no surge windows

    private fun service(osrm: OsrmRouteSource? = stubOsrm, cfg: FareConfig = fares) =
        FareService(fares = { cfg }, osrm = osrm, clock = { fixedHour })

    @Test
    fun `osrm route source is used when reachable`() {
        val r = service().estimate(43.7, 7.42, 43.75, 7.43, "monaco", "standard")
        assertTrue(r is FareService.EstimateOutcome.Ok)
        assertEquals("osrm", r.result.routeSource)
        assertEquals(stubRoute.distanceMeters, r.result.distanceMeters)
    }

    @Test
    fun `osrm null degrades to straight_line with the configured calibration`() {
        val r = service(osrm = null).estimate(43.7384, 7.4246, 43.7543, 7.4312, "monaco", "standard")
        assertTrue(r is FareService.EstimateOutcome.Ok)
        assertEquals("straight_line", r.result.routeSource)
        val expected = FareMath.straightLineRoute(43.7384, 7.4246, 43.7543, 7.4312, 1.3, 22.0)
        assertEquals(expected.distanceMeters, r.result.distanceMeters)
        assertEquals(expected.durationSeconds, r.result.durationSeconds)
    }

    @Test
    fun `osrm failure (throws or null) degrades instead of failing`() {
        val throwing = OsrmRouteSource { _, _, _, _ -> error("osrm down") }
        val r = service(osrm = throwing).estimate(43.7, 7.42, 43.75, 7.43, "monaco", "standard")
        // FareService treats a thrown error from the source as degraded (never a 500).
        assertTrue(r is FareService.EstimateOutcome.Ok, "degradation contract")
        assertEquals("straight_line", r.result.routeSource)
    }

    @Test
    fun `unknown city and unknown ride type are Invalid`() {
        val badCity = service().estimate(43.7, 7.42, 43.75, 7.43, "paris", null)
        assertTrue(badCity is FareService.EstimateOutcome.Invalid)
        assertEquals("unknown_city", badCity.code)
        val badType = service().estimate(43.7, 7.42, 43.75, 7.43, "monaco", "helicopter")
        assertTrue(badType is FareService.EstimateOutcome.Invalid)
        assertEquals("unknown_ride_type", badType.code)
    }

    @Test
    fun `null rideType expands to ALL ride types sorted by low total`() {
        val cfg = fares.copy(
            cities = mapOf(
                "monaco" to CityFares(
                    currency = "EUR",
                    rideTypes = mapOf(
                        "standard" to RideTypeFares(base = 2.5, perKm = 1.8, perMinute = 0.45, minimumFare = 8.0),
                        "xl" to RideTypeFares(base = 5.0, perKm = 2.6, perMinute = 0.6, minimumFare = 12.0),
                        "bike" to RideTypeFares(base = 1.0, perKm = 0.9, perMinute = 0.2, minimumFare = 4.0),
                    ),
                ),
            ),
        )
        val r = service(cfg = cfg).estimate(43.7, 7.42, 43.75, 7.43, "monaco", null)
        assertTrue(r is FareService.EstimateOutcome.Ok)
        assertEquals(listOf("bike", "standard", "xl"), r.result.estimates.map { it.rideType })
    }

    @Test
    fun `estimate carries the surge actually configured for the hour`() {
        val nightHour = java.time.Instant.parse("2026-11-25T23:30:00Z")
        val cfg = fares.copy(
            cities = mapOf(
                "monaco" to CityFares(
                    currency = "EUR",
                    rideTypes = mapOf(
                        "standard" to RideTypeFares(
                            base = 2.5, perKm = 1.8, perMinute = 0.45, minimumFare = 8.0,
                            night = SurgeWindow(multiplier = 1.25, startHourUtc = 22, endHourUtc = 6),
                        ),
                    ),
                ),
            ),
        )
        val svc = FareService(fares = { cfg }, osrm = stubOsrm, clock = { nightHour })
        val r = svc.estimate(43.7, 7.42, 43.75, 7.43, "monaco", "standard")
        assertTrue(r is FareService.EstimateOutcome.Ok)
        val est = r.result.estimates.single()
        assertEquals(1.25, est.surgeMultiplier)
        assertEquals("night", est.surgeReason)
        // High end of the range must include the surge (never under-quote).
        assertTrue(est.highTotal > est.lowTotal)
    }
}

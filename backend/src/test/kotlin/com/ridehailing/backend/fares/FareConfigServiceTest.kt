package com.ridehailing.backend.fares

import com.ridehailing.backend.config.ConfigService
import com.ridehailing.backend.config.defaultFares
import com.ridehailing.backend.config.SignedConfigBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P4-1 fare config validation + the admin edit path (D75 full-replace) with
 * version/audit behavior identical to the login-methods patch path.
 */
class FareConfigServiceTest {

    private fun service() = ConfigService(
        bundle = SignedConfigBundle.inMemoryDevKeys(),
        eventRecorder = { type, _ -> events.add(type) },
    )
    private val events = mutableListOf<String>()

    private val valid = defaultFares()

    @Test
    fun `default fares validate clean and bootstrap monaco standard`() {
        assertTrue(valid.validationErrors().isEmpty())
        assertEquals(1, valid.cities.size)
        assertTrue("standard" in valid.cities["monaco"]!!.rideTypes)
    }

    @Test
    fun `validation rejects negative amounts bad currency bad hours oversized surge`() {
        val bad = FareConfig(
            cities = mapOf(
                "Nice" to CityFares( // uppercase city key
                    currency = "eur", // not 3 uppercase letters
                    rideTypes = mapOf(
                        "Standard" to RideTypeFares( // uppercase ride type key
                            base = -1.0, perKm = 0.0, perMinute = 0.0, minimumFare = 0.0,
                            night = SurgeWindow(multiplier = 6.0, startHourUtc = 24, endHourUtc = -1),
                        ),
                    ),
                ),
            ),
            straightLine = StraightLineCalibration(detourFactor = 0.5, assumedAvgSpeedKmh = 0.0),
        )
        val errs = bad.validationErrors()
        assertTrue(errs.any { it.contains("cities.Nice") }, "city key error: $errs")
        assertTrue(errs.any { it.contains("currency") }, "currency error: $errs")
        assertTrue(errs.any { it.contains("rideTypes.Standard") }, "ride type key error: $errs")
        assertTrue(errs.any { it.contains("base") }, "negative base error: $errs")
        assertTrue(errs.any { it.contains("night.multiplier") }, "oversized surge error: $errs")
        assertTrue(errs.any { it.contains("night.startHourUtc") }, "hour range error: $errs")
        assertTrue(errs.any { it.contains("detourFactor") }, "detour error: $errs")
        assertTrue(errs.any { it.contains("assumedAvgSpeedKmh") }, "speed error: $errs")
    }

    @Test
    fun `updateFares happy path bumps version and audits`() {
        val svc = service()
        val before = svc.currentBundle().version
        val next = svc.updateFares("admin-1", valid)
        assertTrue(next is ConfigService.UpdateResult.Updated)
        assertEquals(before + 1, next.entry.version)
        assertEquals("admin-1", next.entry.changedBy)
        assertTrue("config.fares.updated" in events)
    }

    @Test
    fun `invalid fares never burn a version`() {
        val svc = service()
        val before = svc.currentBundle().version
        val bad = valid.copy(cities = mapOf("monaco" to valid.cities["monaco"]!!.copy(currency = "euro")))
        val r = svc.updateFares("admin-1", bad)
        assertTrue(r is ConfigService.UpdateResult.Invalid)
        assertEquals(before, svc.currentBundle().version)
        assertTrue(events.none { it == "config.fares.updated" })
    }

    @Test
    fun `fares land in the signed device bundle`() {
        val svc = service()
        val canonical = svc.currentBundle().configJson
        assertTrue("\"fares\"" in canonical, "fares section missing from the canonical JSON")
        assertTrue("straightLine" in canonical)
        assertTrue("monaco" in canonical)
    }

    @Test
    fun `surge windows configured in config drive estimates deterministically`() {
        val svc = service()
        val hourFixed = java.time.Instant.parse("2026-11-25T23:10:00Z") // inside night 22..6
        val params = svc.fares.cities["monaco"]!!.rideTypes["standard"]!!.toParams().copy(
            nightMultiplier = 1.25, nightStartHourUtc = 22, nightEndHourUtc = 6,
        )
        val (surge, reason) = FareMath.surgeForHour(params, hourFixed.atZone(java.time.ZoneOffset.UTC).hour)
        assertEquals(1.25, surge)
        assertEquals("night", reason)
    }
}

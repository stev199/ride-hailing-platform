package com.ridehailing.core.data.pricing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-3 fare math KAT (known-answer tests) - THE SERVER VS ON-DEVICE PARITY PROOF
 * (spec §pricing "unit-test the fare calculator extensively, including server vs
 * on-device parity within a stated tolerance").
 *
 * The vector file is the BYTE-IDENTICAL copy of backend/src/test/resources/fare_kat.json
 * (scripts/gen_fare_kat.py, independent Python implementation). Stated tolerance: 0.00
 * exactly - both sides run the same file through identical IEEE-754 arithmetic, and the
 * SHA-256 pin below fails the build the moment either copy drifts.
 *
 * JUnit4 note: assertEquals carries (message, expected, actual, delta) - message FIRST
 * (kotlin.test on the backend suite uses (expected, actual, message); both pin 0.00).
 */
class FareMathKatTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Unit-test classpath resource -> bytes. Reads via NIO (unit tests expose resources
     * as real files); InputStream extensions resolve oddly in this source set, so we
     * never touch them.
     */
    private fun loadVectorBytes(): ByteArray {
        // stdlib error() returns Nothing (org.junit.Assert.fail returns Unit, which
        // would leave `url` nullable and break every member call below).
        val url = javaClass.getResource("/fare_kat.json")
            ?: error("fare_kat.json not found in android test resources")
        return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(url.file))
    }

    private fun loadVectorDoc(): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(loadVectorBytes().decodeToString()).jsonObject

    /** Pins BOTH suites to byte-identical vectors (must equal the backend pin). */
    @Test
    fun `vector file content is pinned`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(loadVectorBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "62242622f4faad6a8822cbaad98b7639e2bfbf6b6821edf3b66da68325e04bed",
            digest,
        )
    }

    @Test
    fun `all KAT vectors reproduce exactly`() {
        val doc = loadVectorDoc()
        val vectors = doc["vectors"]!!.jsonArray
        assertTrue("expected the full vector set, got ${vectors.size}", vectors.size >= 10)
        for (v in vectors) {
            val o = v.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val fares = o["fares"]!!.jsonObject
            val params = FareMath.RideTypeParams(
                base = fares["base"]!!.jsonPrimitive.double,
                perKm = fares["perKm"]!!.jsonPrimitive.double,
                perMinute = fares["perMinute"]!!.jsonPrimitive.double,
                minimumFare = fares["minimumFare"]!!.jsonPrimitive.double,
                bookingFee = fares["bookingFee"]!!.jsonPrimitive.double,
                cancelFee = fares["cancelFee"]!!.jsonPrimitive.double,
                waitFeePerMinute = fares["waitFeePerMinute"]!!.jsonPrimitive.double,
                nightMultiplier = fares["nightMultiplier"]!!.jsonPrimitive.double,
                nightStartHourUtc = fares["nightStartHourUtc"]!!.jsonPrimitive.int,
                nightEndHourUtc = fares["nightEndHourUtc"]!!.jsonPrimitive.int,
                peakMultiplier = fares["peakMultiplier"]!!.jsonPrimitive.double,
                peakStartHourUtc = fares["peakStartHourUtc"]!!.jsonPrimitive.int,
                peakEndHourUtc = fares["peakEndHourUtc"]!!.jsonPrimitive.int,
            )
            val route = o["route"]!!.jsonObject
            val input = FareMath.RouteInput(
                route["distanceMeters"]!!.jsonPrimitive.double,
                route["durationSeconds"]!!.jsonPrimitive.double,
            )
            val hour = o["hourUtc"]!!.jsonPrimitive.int
            val expect = o["expect"]!!.jsonObject

            // Surge selection from the hour.
            val (surge, reason) = FareMath.surgeForHour(params, hour)
            assertEquals(
                "$name: surge",
                expect["surgeMultiplier"]!!.jsonPrimitive.double, surge, 0.0,
            )
            val expectedReason = expect["surgeReason"]
            assertEquals(
                "$name: surge reason",
                if (expectedReason is kotlinx.serialization.json.JsonNull) {
                    null
                } else {
                    expectedReason!!.jsonPrimitive.content
                },
                reason,
            )

            // Range: low = no surge, high = max surge.
            val range = FareMath.estimateRange(params, input, expect["maxSurge"]!!.jsonPrimitive.double)
            assertBreakdown(name, "low", expect["low"]!!.jsonObject, range.low)
            assertBreakdown(name, "high", expect["high"]!!.jsonObject, range.high)
        }
    }

    private fun assertBreakdown(
        name: String,
        leg: String,
        expected: kotlinx.serialization.json.JsonObject,
        actual: FareMath.FareBreakdown,
    ) {
        assertEquals("$name.$leg.base", expected["base"]!!.jsonPrimitive.double, actual.base, 0.0)
        assertEquals("$name.$leg.distanceCost", expected["distanceCost"]!!.jsonPrimitive.double, actual.distanceCost, 0.0)
        assertEquals("$name.$leg.timeCost", expected["timeCost"]!!.jsonPrimitive.double, actual.timeCost, 0.0)
        assertEquals("$name.$leg.surge", expected["surgeMultiplier"]!!.jsonPrimitive.double, actual.surgeMultiplier, 0.0)
        assertEquals("$name.$leg.minimumApplied", expected["minimumApplied"]!!.jsonPrimitive.boolean, actual.minimumApplied)
        assertEquals("$name.$leg.bookingFee", expected["bookingFee"]!!.jsonPrimitive.double, actual.bookingFee, 0.0)
        assertEquals("$name.$leg.total", expected["total"]!!.jsonPrimitive.double, actual.total, 0.0)
    }

    @Test
    fun `haversine and straight-line fallback reproduce the committed vector`() {
        // Monaco coords from the straight_line_fallback vector, recomputed directly.
        val meters = FareMath.haversineMeters(43.7384, 7.4246, 43.7543, 7.4312) * 1.3
        val seconds = meters / 1000.0 / 22.0 * 3600.0
        val doc = loadVectorDoc()
        val v = doc["vectors"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "straight_line_fallback" }
        val route = v["route"]!!.jsonObject
        assertEquals(route["distanceMeters"]!!.jsonPrimitive.double, meters, 1e-6)
        assertEquals(route["durationSeconds"]!!.jsonPrimitive.double, seconds, 1e-6)
    }
}

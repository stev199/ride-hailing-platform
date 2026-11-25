package com.ridehailing.backend.fares

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P4-1 fare math KAT (known-answer tests) against the committed vector file
 * scripts/gen_fare_kat.py generated with an INDEPENDENT Python implementation
 * of the same math (IEEE-754 doubles are bit-identical across JVM/ART/CPython;
 * the generator refuses inputs near rounding boundaries).
 *
 * The SAME file is committed under the android test resources (P4-3) with the
 * same SHA-256 pin - that is the server vs on-device parity proof (spec §pricing
 * "unit-test the fare calculator extensively, including server vs on-device
 * parity within a stated tolerance"; tolerance is 0.00 here because both sides
 * run the identical file through identical arithmetic).
 */
class FareMathKatTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectorDoc(): kotlinx.serialization.json.JsonObject {
        val stream = javaClass.getResourceAsStream("/fare_kat.json")
            ?: fail("fare_kat.json not found in test resources")
        return json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
    }

    /** Pins BOTH suites to byte-identical vectors (android copy must match). */
    @Test
    fun `vector file content is pinned`() {
        val bytes = javaClass.getResourceAsStream("/fare_kat.json")!!.readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("62242622f4faad6a8822cbaad98b7639e2bfbf6b6821edf3b66da68325e04bed", digest)
    }

    @Test
    fun `all KAT vectors reproduce exactly`() {
        val doc = loadVectorDoc()
        val vectors = doc["vectors"]!!.jsonArray
        assertTrue(vectors.size >= 10, "expected the full vector set, got ${vectors.size}")
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
            assertEquals(expect["surgeMultiplier"]!!.jsonPrimitive.double, surge, "$name: surge")
            val expectedReason = expect["surgeReason"]
            assertEquals(
                if (expectedReason is kotlinx.serialization.json.JsonNull) null else expectedReason!!.jsonPrimitive.content,
                reason,
                "$name: surge reason",
            )

            // Range: low = no surge, high = max surge.
            val range = FareMath.estimateRange(params, input, expect["maxSurge"]!!.jsonPrimitive.double)
            assertBreakdown(name, "low", expect["low"]!!.jsonObject, range.low)
            assertBreakdown(name, "high", expect["high"]!!.jsonObject, range.high)
        }
    }

    private fun assertBreakdown(name: String, leg: String, expected: kotlinx.serialization.json.JsonObject, actual: FareMath.FareBreakdown) {
        assertEquals(expected["base"]!!.jsonPrimitive.double, actual.base, "$name.$leg.base")
        assertEquals(expected["distanceCost"]!!.jsonPrimitive.double, actual.distanceCost, "$name.$leg.distanceCost")
        assertEquals(expected["timeCost"]!!.jsonPrimitive.double, actual.timeCost, "$name.$leg.timeCost")
        assertEquals(expected["surgeMultiplier"]!!.jsonPrimitive.double, actual.surgeMultiplier, "$name.$leg.surge")
        assertEquals(expected["minimumApplied"]!!.jsonPrimitive.boolean, actual.minimumApplied, "$name.$leg.minimumApplied")
        assertEquals(expected["bookingFee"]!!.jsonPrimitive.double, actual.bookingFee, "$name.$leg.bookingFee")
        assertEquals(expected["total"]!!.jsonPrimitive.double, actual.total, "$name.$leg.total")
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

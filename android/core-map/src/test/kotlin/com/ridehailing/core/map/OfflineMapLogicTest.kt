package com.ridehailing.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure offline-map logic (P3-1): metadata codec round-trip + progress math edge cases. */
class OfflineMapLogicTest {
    @Test
    fun metadata_roundTrips() {
        val meta = OfflineRegionMeta(name = "city-extract", createdAtEpochSec = 1_737_600_000)
        val bytes = OfflineRegionMetaCodec.encode(meta)
        assertEquals(meta, OfflineRegionMetaCodec.decode(bytes))
    }

    @Test
    fun metadata_decode_failure_degradesToNull() {
        assertNull(OfflineRegionMetaCodec.decode("<html>garbage".toByteArray()))
        assertNull(OfflineRegionMetaCodec.decode(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun progress_percent_math_and_clamping() {
        assertEquals(0, OfflineProgressMath.percent(0, 100))
        assertEquals(50, OfflineProgressMath.percent(50, 100))
        assertEquals(100, OfflineProgressMath.percent(100, 100))
        // over-reporting on retries must never exceed 100
        assertEquals(100, OfflineProgressMath.percent(120, 100))
        // nothing required yet -> 0 (no division by zero)
        assertEquals(0, OfflineProgressMath.percent(0, 0))
        assertEquals(0, OfflineProgressMath.percent(10, 0))
    }

    @Test
    fun mapConfig_defaultRegion_staysInsideWebMercatorBounds() {
        val config = MapConfig(styleUrl = "http://10.0.2.2:8081/styles/basic-preview/style.json")
        // SPAN_DEG around the Monaco center must stay within the Web Mercator latitude
        // clip (|lat| <= 85) that OfflineTilePyramidRegionDefinition requires
        val north = (config.centerLat + OfflineMapManager.SPAN_DEG)
        val south = (config.centerLat - OfflineMapManager.SPAN_DEG)
        assertTrue(north <= 85.0 && south >= -85.0)
        assertTrue(north > south)
    }
}

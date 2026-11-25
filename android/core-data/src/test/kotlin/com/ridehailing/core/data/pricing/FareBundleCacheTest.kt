package com.ridehailing.core.data.pricing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FareBundleCache on plain JVM (DataStore is file-based, no Android needed -
 * StoresTest pattern). Pins: roundtrip persistence, ANTI-ROLLBACK version gate,
 * and the spec §pricing expiry/stale math with exact boundaries.
 */
class FareBundleCacheTest {
    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    /** Injected clock - every test pins it so fetchedAt is deterministic. */
    private var nowMs: Long = 1_700_000_000_000L

    private fun newCache(): FareBundleCache = FareBundleCache(
        PreferenceDataStoreFactory.create(scope = scope) { file },
        clockMs = { nowMs },
    )

    private fun env(version: Long, config: String = CANONICAL) =
        FareBundleEnvelopeDto(
            version = version,
            issuedAtEpochSec = nowMs / 1000,
            config = config,
            signature = "00",
            publicKey = "00",
        )

    @Before
    fun setUp() {
        tmp = TemporaryFolder()
        tmp.create()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Do NOT pre-create the file: DataStore creates/closes it itself.
        file = File(tmp.root, "farebundle_test.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.delete()
    }

    @Test
    fun `empty cache reads as null with version zero`() = runBlocking {
        val cache = newCache()
        assertNull(cache.bundle())
        assertEquals(0L, cache.lastAcceptedVersion())
    }

    @Test
    fun `save then bundle roundtrips every field`() = runBlocking {
        val cache = newCache()
        assertTrue(cache.save(env(version = 3, config = CANONICAL), signatureVerified = true))
        val b = cache.bundle()!!
        assertEquals(3L, b.version)
        assertEquals(CANONICAL, b.configJson)
        assertEquals(nowMs / 1000, b.issuedAtEpochSec)
        assertEquals(nowMs / 1000, b.fetchedAtEpochSec)
        assertEquals(true, b.signatureVerified)
        assertEquals(3L, cache.lastAcceptedVersion())
    }

    @Test
    fun `anti-rollback rejects versions at or below the last accepted`() = runBlocking {
        val cache = newCache()
        assertTrue(cache.save(env(version = 5), null))
        assertFalse("same version must be rejected", cache.save(env(version = 5), null))
        assertFalse("older version must be rejected", cache.save(env(version = 4), null))
        // Rejected saves must NOT touch the stored bundle.
        assertEquals(5L, cache.bundle()!!.version)
        assertTrue(cache.save(env(version = 6), null))
        assertEquals(6L, cache.bundle()!!.version)
    }

    @Test
    fun `stale flag respects the ttl boundary exactly`() = runBlocking {
        val cache = newCache()
        assertTrue(cache.save(env(version = 1), null))
        val b = cache.bundle()!!
        val fetchedAt = b.fetchedAtEpochSec
        assertFalse("exactly ttl seconds old is NOT stale", cache.isStale(b, fetchedAt + 86_400))
        assertTrue("one second past ttl IS stale", cache.isStale(b, fetchedAt + 86_401))
        assertTrue("far past ttl IS stale", cache.isStale(b, fetchedAt + 3 * 86_400))
    }

    @Test
    fun `signatureVerified persists as true false and null-unsupported`() = runBlocking {
        val cache = newCache()
        assertTrue(cache.save(env(version = 1), true))
        assertEquals(true, cache.bundle()!!.signatureVerified)
        assertTrue(cache.save(env(version = 2), false))
        assertEquals(false, cache.bundle()!!.signatureVerified)
        assertTrue(cache.save(env(version = 3), null))
        assertNull("unsupported platform must surface as null, not false", cache.bundle()!!.signatureVerified)
    }

    companion object {
        /** Canonical-config shape: loginMethods + fares wrapper (ConfigService.configCanonicalJson). */
        private const val CANONICAL =
            """{"loginMethods":{"passwordLoginEnabled":true},"fares":{"cities":{"monaco":{""" +
                """"currency":"EUR","rideTypes":{"standard":{"base":2.5,"perKm":1.8,"perMinute":0.45,""" +
                """"minimumFare":8.0,"bookingFee":1.5,"cancelFee":3.0,"waitFeePerMinute":0.5,""" +
                """"night":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0},"peak":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0}}}},""" +
                """"straightLine":{"detourFactor":1.3,"assumedAvgSpeedKmh":22.0},""" +
                """"quote":{"validitySeconds":90,"mode":"upfront_locked","meteredCapMultiplier":1.5}}}"""
    }
}

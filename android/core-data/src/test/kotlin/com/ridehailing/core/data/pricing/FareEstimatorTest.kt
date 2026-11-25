package com.ridehailing.core.data.pricing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ridehailing.core.data.network.ApiClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
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
 * P4-3 device estimator stack on plain JVM: FareBundleVerifier (real Ed25519 - the
 * JVM HAS the provider, so the true/false paths are exercised for real here; the
 * null-unsupported path only exists on Android < 33), FareBundleFetcher (MockEngine,
 * AuthRepositoryTest pattern - response bodies go through the real ContentNegotiation
 * plugin), FareEstimator (offline route selection, surge range, stale flag).
 *
 * The numeric expectations below are cross-checked against fare_kat.json: the noon
 * standard estimate (5234.5 m / 720 s, fares base 2.5 / perKm 1.8 / perMinute 0.45 /
 * minimum 8.0 / booking 1.5) reproduces KAT vector standard_no_surge total 18.82.
 */
class FareEstimatorTest {
    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    private var nowMs: Long = 1_732_500_000_000L

    private fun newCache(name: String = "farebundle_test"): FareBundleCache = FareBundleCache(
        // One DataStore INSTANCE per file - a second instance on the same file throws
        // ("multiple DataStores active"); tests that need two caches pass distinct names.
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") },
        clockMs = { nowMs },
    )

    private var responder: suspend (path: String) -> FakeResponse = { _ ->
        throw AssertionError("unexpected request")
    }

    private data class FakeResponse(val status: Int, val text: String)

    private fun client(): HttpClient = ApiClientFactory.create(
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val raw = responder(path)
            respond(
                content = ByteReadChannel(raw.text),
                status = HttpStatusCode.fromValue(raw.status),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Before
    fun setUp() {
        tmp = TemporaryFolder()
        tmp.create()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(tmp.root, "farebundle_test.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.delete()
    }

    // ---------- helpers ----------

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Minimal JSON string literal (my canonical configs contain quotes, nothing exotic). */
    private fun jsonEscape(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun envelopeBody(env: FareBundleEnvelopeDto): String =
        """{"version":${env.version},"issuedAtEpochSec":${env.issuedAtEpochSec},""" +
            """"config":${jsonEscape(env.config)},""" +
            """"signature":"${env.signature}","publicKey":"${env.publicKey}"}"""

    private fun signedEnvelope(kp: KeyPair, version: Long, config: String): FareBundleEnvelopeDto {
        val issuedAt = nowMs / 1000
        val content = "$version|$config|$issuedAt"
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(content.toByteArray(Charsets.UTF_8))
        }.sign()
        return FareBundleEnvelopeDto(
            version = version,
            issuedAtEpochSec = issuedAt,
            config = config,
            signature = hex(sig),
            publicKey = hex(kp.public.encoded),
        )
    }

    /** Canonical-config builder mirroring ConfigService.configCanonicalJson (fares wrapper). */
    private fun canonicalConfig(
        nightMultiplier: Double = 1.0,
        nightStart: Int = 0,
        nightEnd: Int = 0,
        detourFactor: Double = 1.3,
        speed: Double = 22.0,
    ): String {
        fun rideType(name: String, base: Double, perKm: Double, perMinute: Double, minimum: Double) =
            """"$name":{"base":$base,"perKm":$perKm,"perMinute":$perMinute,"minimumFare":$minimum,""" +
                """"bookingFee":1.5,"cancelFee":3.0,"waitFeePerMinute":0.5,""" +
                """"night":{"multiplier":$nightMultiplier,"startHourUtc":$nightStart,"endHourUtc":$nightEnd},""" +
                """"peak":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0}}"""
        return "{" +
            "\"loginMethods\":{\"passwordLoginEnabled\":true}," +
            "\"fares\":{" +
            "\"cities\":{\"monaco\":{\"currency\":\"EUR\",\"rideTypes\":{" +
            rideType("standard", 2.5, 1.8, 0.45, 8.0) + "," +
            rideType("van", 3.0, 2.4, 0.6, 10.0) +
            "}}}," +
            "\"straightLine\":{\"detourFactor\":$detourFactor,\"assumedAvgSpeedKmh\":$speed}," +
            "\"quote\":{\"validitySeconds\":90,\"mode\":\"upfront_locked\",\"meteredCapMultiplier\":1.5}" +
            "}}"
    }

    private val bundleErrorBody = """{"code":"http_500","message":"boom"}"""

    // ---------- FareBundleVerifier ----------

    @Test
    fun `verifier accepts a correctly signed bundle`() {
        val kp = keyPair()
        val env = signedEnvelope(kp, 1, canonicalConfig())
        assertEquals(true, FareBundleVerifier.verify(env))
    }

    @Test
    fun `verifier rejects a tampered config and a wrong key`() {
        val kp = keyPair()
        val env = signedEnvelope(kp, 1, canonicalConfig())
        val tampered = env.copy(config = canonicalConfig(detourFactor = 9.9))
        assertEquals("tampered payload must not verify", false, FareBundleVerifier.verify(tampered))
        val otherKey = signedEnvelope(keyPair(), 1, canonicalConfig())
        assertEquals(
            "signature made with another key must not verify",
            false,
            FareBundleVerifier.verify(env.copy(publicKey = otherKey.publicKey)),
        )
        assertEquals("garbage hex must map to false, not throw", false, FareBundleVerifier.verify(env.copy(signature = "zz")))
    }

    // ---------- FareBundleFetcher ----------

    @Test
    fun `fetcher stores a fresh verified bundle`() = runBlocking {
        val kp = keyPair()
        val env = signedEnvelope(kp, 4, canonicalConfig())
        responder = { path ->
            assertEquals("/v1/config", path)
            val payload = """{"version":${env.version},"issuedAtEpochSec":${env.issuedAtEpochSec},""" +
                """"config":${jsonEscape(env.config)},""" +
                """"signature":"${env.signature}","publicKey":"${env.publicKey}"}"""
            FakeResponse(200, payload)
        }
        val fetcher = FareBundleFetcher(client(), newCache())
        val result = fetcher.refresh()
        assertTrue("got $result", result is FareBundleFetcher.FetchResult.Refreshed)
        result as FareBundleFetcher.FetchResult.Refreshed
        assertEquals(4L, result.version)
        assertEquals("JVM has Ed25519 - verification must run", true, result.signatureVerified)
    }

    @Test
    fun `fetcher maps http errors to Unreachable and never throws`() = runBlocking {
        responder = { FakeResponse(500, bundleErrorBody) }
        val result = FareBundleFetcher(client(), newCache()).refresh()
        assertTrue("got $result", result is FareBundleFetcher.FetchResult.Unreachable)
        result as FareBundleFetcher.FetchResult.Unreachable
        assertTrue(result.reason.contains("500"))
    }

    @Test
    fun `fetcher rejects rollback and stores nothing`() = runBlocking {
        val kp = keyPair()
        val cache = newCache()
        val v5 = signedEnvelope(kp, 5, canonicalConfig())
        assertTrue(cache.save(v5, true))
        val env = signedEnvelope(kp, 5, canonicalConfig(detourFactor = 1.5))
        responder = { _ ->
            FakeResponse(
                200,
                """{"version":${env.version},"issuedAtEpochSec":${env.issuedAtEpochSec},""" +
                    """"config":${jsonEscape(env.config)},""" +
                    """"signature":"${env.signature}","publicKey":"${env.publicKey}"}""",
            )
        }
        val result = FareBundleFetcher(client(), cache).refresh()
        assertTrue("got $result", result is FareBundleFetcher.FetchResult.RolledBack)
        assertEquals("cache untouched on rollback", 5L, cache.lastAcceptedVersion())
        assertEquals(1.3, cachedStraightLine(cache), 0.0)
    }

    @Test
    fun `fetcher refuses an unverifiable bundle and leaves the cache untouched`() = runBlocking {
        val kp = keyPair()
        val cache = newCache()
        val good = signedEnvelope(kp, 1, canonicalConfig())
        val env = good.copy(config = canonicalConfig(detourFactor = 9.9)) // tampered after signing
        responder = { _ ->
            FakeResponse(
                200,
                """{"version":${env.version},"issuedAtEpochSec":${env.issuedAtEpochSec},""" +
                    """"config":${jsonEscape(env.config)},""" +
                    """"signature":"${env.signature}","publicKey":"${env.publicKey}"}""",
            )
        }
        val result = FareBundleFetcher(client(), cache).refresh()
        assertTrue("got $result", result is FareBundleFetcher.FetchResult.BadBundle)
        assertNull("tampered bundle must NOT poison the cache", cache.bundle())
        // A GOOD bundle still stores afterwards.
        val envOk = signedEnvelope(kp, 2, canonicalConfig())
        responder = { _ ->
            FakeResponse(
                200,
                """{"version":${envOk.version},"issuedAtEpochSec":${envOk.issuedAtEpochSec},""" +
                    """"config":${jsonEscape(envOk.config)},""" +
                    """"signature":"${envOk.signature}","publicKey":"${envOk.publicKey}"}""",
            )
        }
        assertTrue(FareBundleFetcher(client(), cache).refresh() is FareBundleFetcher.FetchResult.Refreshed)
        assertEquals(2L, cache.bundle()!!.version)
    }

    // ---------- FareEstimator ----------

    /** 2024-11-25T12:00:00Z (hour 12) and 23:00Z (hour 23, inside a 22..6 night window). */
    private val noonUtcSec = 1_732_540_800L
    private val lateNightUtcSec = noonUtcSec + 11 * 3600

    private suspend fun seed(version: Long = 1, config: String = canonicalConfig()): FareBundleCache {
        val cache = newCache()
        val kp = keyPair()
        assertTrue(cache.save(signedEnvelope(kp, version, config), signatureVerified = true))
        return cache
    }

    private suspend fun cachedStraightLine(cache: FareBundleCache): Double {
        val cfg = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CanonicalConfigDto.serializer(), cache.bundle()!!.configJson)
        return cfg.fares.straightLine.detourFactor
    }

    private suspend fun ok(
        estimator: FareEstimator,
        nowSec: Long,
        cachedRoute: FareMath.RouteInput? = FareMath.RouteInput(5234.5, 720.0),
    ): OfflineEstimateResult {
        val out = estimator.estimate(
            pickupLat = 43.7384, pickupLon = 7.4246,
            dropoffLat = 43.7543, dropoffLon = 7.4312,
            city = "monaco", rideType = null,
            cachedRoute = cachedRoute, nowEpochSec = nowSec,
        )
        assertTrue("got $out", out is OfflineEstimateOutcome.Ok)
        return (out as OfflineEstimateOutcome.Ok).result
    }

    @Test
    fun `noon cached-route estimate reproduces the KAT vector and sorts by lowTotal`() = runBlocking {
        val estimator = FareEstimator(seed(), clockMs = { nowMs })
        val r = ok(estimator, noonUtcSec)
        assertEquals("cached_route", r.routeSource)
        assertEquals(5234.5, r.distanceMeters, 0.0)
        assertEquals(720.0, r.durationSeconds, 0.0)
        assertFalse(r.staleConfig)
        assertEquals(2, r.estimates.size)
        assertEquals("standard", r.estimates[0].rideType)
        // KAT standard_no_surge: distance 9.42 + time 5.40 + base 2.50 + booking 1.50 = 18.82.
        assertEquals(18.82, r.estimates[0].lowTotal, 0.0)
        assertEquals(18.82, r.estimates[0].highTotal, 0.0)
        assertNull(r.estimates[0].surgeReason)
        assertEquals("van", r.estimates[1].rideType)
        // 3.0 + (12.5628 + 7.2) + 1.5 = 24.2628 -> roundMoney -> 24.26.
        assertEquals(24.26, r.estimates[1].lowTotal, 0.0)
    }

    @Test
    fun `night surge widens the range and names the reason`() = runBlocking {
        val estimator = FareEstimator(
            seed(1, canonicalConfig(nightMultiplier = 1.4, nightStart = 22, nightEnd = 6)),
            clockMs = { nowMs },
        )
        val r = ok(estimator, lateNightUtcSec)
        assertEquals(1.4, r.estimates[0].surgeMultiplier, 0.0)
        assertEquals("night", r.estimates[0].surgeReason)
        assertEquals(18.82, r.estimates[0].lowTotal, 0.0)
        // subtotal 2.5 + (9.4221 + 5.4) * 1.4 = 23.25094 (+ booking 1.5) -> 24.75.
        assertEquals(24.75, r.estimates[0].highTotal, 0.0)
    }

    @Test
    fun `offline high end never under-quotes a configured surge even off-window`() = runBlocking {
        val estimator = FareEstimator(
            seed(1, canonicalConfig(nightMultiplier = 1.4, nightStart = 22, nightEnd = 6)),
            clockMs = { nowMs },
        )
        // Noon: no live surge, but the range high end still assumes the max multiplier.
        val r = ok(estimator, noonUtcSec)
        assertEquals(1.0, r.estimates[0].surgeMultiplier, 0.0)
        assertNull(r.estimates[0].surgeReason)
        assertEquals(18.82, r.estimates[0].lowTotal, 0.0)
        assertEquals(24.75, r.estimates[0].highTotal, 0.0)
    }

    @Test
    fun `straight-line fallback uses the cached calibration`() = runBlocking {
        val estimator = FareEstimator(
            seed(1, canonicalConfig(detourFactor = 1.6, speed = 30.0)),
            clockMs = { nowMs },
        )
        val r = ok(estimator, noonUtcSec, cachedRoute = null)
        assertEquals("straight_line", r.routeSource)
        val expectedMeters = FareMath.haversineMeters(43.7384, 7.4246, 43.7543, 7.4312) * 1.6
        assertEquals(expectedMeters, r.distanceMeters, 0.0)
        assertEquals(expectedMeters / 1000.0 / 30.0 * 3600.0, r.durationSeconds, 0.0)
    }

    @Test
    fun `stale cache still estimates but flags it`() = runBlocking {
        val cache = seed()
        val estimator = FareEstimator(cache, clockMs = { nowMs })
        val fetchedAt = cache.bundle()!!.fetchedAtEpochSec
        val fresh = ok(estimator, fetchedAt + 3_600)
        assertFalse(fresh.staleConfig)
        val stale = ok(estimator, fetchedAt + 86_401)
        assertTrue(stale.staleConfig)
        assertEquals(1L, stale.bundleVersion)
        assertEquals(true, stale.signatureVerified)
    }

    @Test
    fun `unknown city ride type and empty cache give typed outcomes`() = runBlocking {
        val emptyCache = newCache("farebundle_empty")
        val estimator = FareEstimator(emptyCache, clockMs = { nowMs })
        val out = estimator.estimate(0.0, 0.0, 0.0, 0.0, "monaco", nowEpochSec = noonUtcSec)
        assertTrue("got $out", out is OfflineEstimateOutcome.NoConfig)

        val seeded = FareEstimator(seed(), clockMs = { nowMs })
        assertTrue(
            seeded.estimate(0.0, 0.0, 0.0, 0.0, "paris", nowEpochSec = noonUtcSec)
                is OfflineEstimateOutcome.UnknownCity,
        )
        assertTrue(
            seeded.estimate(0.0, 0.0, 0.0, 0.0, "monaco", rideType = "suv", nowEpochSec = noonUtcSec)
                is OfflineEstimateOutcome.UnknownRideType,
        )
        // rideType filter narrows to exactly that type.
        val r = ok(seeded, noonUtcSec)
        val single = seeded.estimate(
            43.7384, 7.4246, 43.7543, 7.4312, "monaco",
            rideType = "standard", cachedRoute = FareMath.RouteInput(5234.5, 720.0),
            nowEpochSec = noonUtcSec,
        )
        assertTrue(single is OfflineEstimateOutcome.Ok)
        assertEquals(1, (single as OfflineEstimateOutcome.Ok).result.estimates.size)
        assertEquals(2, r.estimates.size)
    }
}

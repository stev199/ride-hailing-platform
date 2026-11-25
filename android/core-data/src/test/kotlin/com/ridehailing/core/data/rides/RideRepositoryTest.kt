package com.ridehailing.core.data.rides

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ridehailing.core.data.db.AppDatabase
import com.ridehailing.core.data.db.statusFromServer
import com.ridehailing.core.data.network.FareBreakdownDto
import com.ridehailing.core.data.network.FareEstimateResponseDto
import com.ridehailing.core.data.network.RideDto
import com.ridehailing.core.data.network.RideListDto
import com.ridehailing.core.data.network.RideTypeEstimateDto
import com.ridehailing.core.data.network.QuoteDto
import com.ridehailing.core.data.network.ApiClientFactory
import com.ridehailing.core.data.network.ApiJson
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.pricing.FareBundleCache
import com.ridehailing.core.data.pricing.FareBundleEnvelopeDto
import com.ridehailing.core.data.pricing.FareEstimator
import com.ridehailing.core.data.pricing.CachedRoute
import com.ridehailing.core.data.pricing.RideTypeEstimate
import com.ridehailing.core.data.pricing.FareMath
import com.ridehailing.core.data.pricing.OfflineEstimateOutcome
import com.ridehailing.core.data.prefs.Session
import com.ridehailing.core.data.prefs.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4-4 rides repository end-to-end on plain JVM (Robolectric for Room + DataStore,
 * MockEngine for the wire - AuthRepositoryTest pattern). Pins the offline-first book
 * path (local truth -> outbox -> server ack backfill), estimate server-first with
 * route caching + offline fallback, cancel status flip, and history dedupe by serverId.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideRepositoryTest {
    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var db: AppDatabase
    private lateinit var sessionFile: File
    private lateinit var fareFile: File
    private lateinit var sessions: SessionStore
    private lateinit var cache: FareBundleCache

    /** Fixed clock: 2024-11-25T12:00:00Z. */
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochSecond(1_732_540_800), ZoneOffset.UTC)

    private data class FakeResponse(val status: Int, val text: String)

    private var responder: suspend (method: String, path: String) -> FakeResponse = { _, _ ->
        throw AssertionError("unexpected request")
    }

    private fun client(): HttpClient = ApiClientFactory.create(
        engine = MockEngine { request ->
            val raw = responder(request.method.value, request.url.encodedPath)
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionFile = File(tmp.root, "session_test.preferences_pb")
        fareFile = File(tmp.root, "farebundle_test.preferences_pb")
        // ONE DataStore instance per file for the whole test (a second instance on the
        // same file throws "multiple DataStores active").
        sessions = SessionStore(
            PreferenceDataStoreFactory.create(scope = scope) { sessionFile },
        )
        cache = FareBundleCache(
            PreferenceDataStoreFactory.create(scope = scope) { fareFile },
            clockMs = { fixedClock.millis() },
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        tmp.delete()
    }

    private fun newRepo(http: HttpClient): RideRepository {
        val auth = AuthRepository(http, sessions)
        return RideRepository(
            dao = db.rideDao(),
            outbox = com.ridehailing.core.data.outbox.OutboxRepository(
                db.outboxDao(), fixedClock,
            ),
            auth = auth,
            http = http,
            fareCache = cache,
            bundleFetcher = com.ridehailing.core.data.pricing.FareBundleFetcher(http, cache),
            estimator = FareEstimator(cache, clockMs = { fixedClock.millis() }),
            ridesTransport = RidesOutboxTransport(http, auth, db, fixedClock),
            clock = fixedClock,
        )
    }

    private suspend fun signIn(http: HttpClient) {
        sessions.save(Session(accessToken = "tok-1", refreshToken = "ref-1", userId = "u1", phone = null))
        // Seed the fare bundle so the offline estimator has config (signature skip = null).
        cache.save(signedEnvelope(), signatureVerified = null)
        // Silence the wire unless a test overrides the responder below.
        responder = { method, path ->
            when {
                method == "POST" && path == "/v1/rides" -> FakeResponse(201, rideJson("srv-1", "REQUESTED"))
                method == "POST" && path == "/v1/rides/estimate" ->
                    FakeResponse(200, estimateJson())
                method == "POST" && path == "/v1/rides/srv-1/cancel" ->
                    FakeResponse(200, rideJson("srv-1", "CANCELLED_BY_RIDER"))
                method == "GET" && path == "/v1/rides" ->
                    FakeResponse(200, historyJson())
                else -> FakeResponse(500, """{"code":"http_500","message":"boom"}""")
            }
        }
        Unit
    }

    private fun signedEnvelope(version: Long = 1) = FareBundleEnvelopeDto(
        version = version,
        issuedAtEpochSec = fixedClock.instant().epochSecond,
        config = """{"fares":{"cities":{"monaco":{"currency":"EUR","rideTypes":{"standard":{""" +
            """"base":2.5,"perKm":1.8,"perMinute":0.45,"minimumFare":8.0,"bookingFee":1.5,""" +
            """"cancelFee":3.0,"waitFeePerMinute":0.5,"night":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0},""" +
            """"peak":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0}}}}},""" +
            """"straightLine":{"detourFactor":1.3,"assumedAvgSpeedKmh":22.0},""" +
            """"quote":{"validitySeconds":90,"mode":"upfront_locked","meteredCapMultiplier":1.5}}}""",
        signature = "00",
        publicKey = "00",
    )

    private fun quote() = QuoteDto(
        amountMinor = 1882, currency = "EUR", mode = "upfront_locked",
        meteredCapMinor = 2823, issuedAtEpochSec = 1_732_540_800,
        validUntilEpochSec = 1_732_540_890, payload = "{}", signature = "aa", publicKeyHex = "bb",
    )

    private fun rideJson(id: String, status: String): String {
        val dto = RideDto(
            id = id, status = status, city = "monaco", rideType = "standard",
            paymentMethod = "CASH", note = null,
            pickupLat = 43.7384, pickupLon = 7.4246, dropoffLat = 43.7543, dropoffLon = 7.4312,
            pickupName = "Pickup", dropoffName = "Dropoff", quote = quote(),
            cancelFeeMinor = 300, createdAtEpochSec = 1_732_540_800, updatedAtEpochSec = 1_732_540_800,
        )
        return ApiJson.encodeToString(RideDto.serializer(), dto)
    }

    private fun estimateJson(): String {
        val dto = FareEstimateResponseDto(
            city = "monaco", routeSource = "osrm", distanceMeters = 5234.5, durationSeconds = 720.0,
            estimates = listOf(
                RideTypeEstimateDto(
                    rideType = "standard", currency = "EUR",
                    lowTotal = 18.82, highTotal = 18.82, surgeMultiplier = 1.0, surgeReason = null,
                    breakdown = FareBreakdownDto(
                        base = 2.5, distanceCost = 9.42, timeCost = 5.4,
                        surgeMultiplier = 1.0, surgeReason = null, minimumApplied = false,
                        bookingFee = 1.5, total = 18.82,
                    ),
                ),
            ),
        )
        return ApiJson.encodeToString(FareEstimateResponseDto.serializer(), dto)
    }

    private fun historyJson(): String {
        val dto = RideListDto(
            rides = listOf(
                RideDto(
                    id = "srv-1", status = "REQUESTED", city = "monaco", rideType = "standard",
                    paymentMethod = "CASH", note = null,
                    pickupLat = 43.7384, pickupLon = 7.4246, dropoffLat = 43.7543, dropoffLon = 7.4312,
                    pickupName = "Pickup", dropoffName = "Dropoff", quote = quote(),
                    cancelFeeMinor = 300, createdAtEpochSec = 1_732_540_800,
                    updatedAtEpochSec = 1_732_540_800,
                ),
                RideDto(
                    id = "srv-2", status = "COMPLETED", city = "monaco", rideType = "van",
                    paymentMethod = "CASH", note = null,
                    pickupLat = 43.74, pickupLon = 7.42, dropoffLat = 43.75, dropoffLon = 7.43,
                    pickupName = "Old", dropoffName = "Trip",
                    quote = quote().copy(amountMinor = 2426), cancelFeeMinor = 0,
                    createdAtEpochSec = 1_732_400_000, updatedAtEpochSec = 1_732_400_500,
                ),
            ),
            limit = 20, offset = 0,
        )
        return ApiJson.encodeToString(RideListDto.serializer(), dto)
    }

    private val draft = RideDraft(
        pickupLabel = "Pickup", pickupLat = 43.7384, pickupLng = 7.4246,
        dropoffLabel = "Dropoff", dropoffLat = 43.7543, dropoffLng = 7.4312,
        city = "monaco", rideType = "standard",
    )

    @Test
    fun book_online_backfillsServerIdAndSignedQuote() = runTest {
        val http = client()
        signIn(http)
        val repo = newRepo(http)
        val rideId = repo.book(draft)
        val entity = db.rideDao().byId(rideId)
        val rows = db.outboxDao().observeUnsent().first()
        assertTrue(
            "serverId not backfilled; outbox rows=${rows.joinToString { "${it.type}/${it.state}/${it.lastError}" }}",
            entity != null && entity!!.serverId == "srv-1",
        )
        assertEquals("REQUESTED", entity!!.status.name)
        assertEquals(1882L, entity.fareMinor)
        assertEquals("EUR", entity.currency)
        assertNotNull(db.rideDao().byServerId("srv-1"))
    }

    @Test
    fun book_offline_keepsLocalTruthWithoutServerId() = runTest {
        val http = client()
        signIn(http)
        responder = { _, _ -> throw java.io.IOException("offline") }
        val repo = newRepo(http)
        val rideId = repo.book(draft)
        val entity = db.rideDao().byId(rideId)
        assertNotNull(entity)
        assertNull("local truth first - no server id until a drain acks", entity!!.serverId)
        assertEquals("REQUESTED", entity.status.name)
    }

    @Test
    fun estimate_online_cachesRouteForTheOfflineEstimator() = runTest {
        val http = client()
        signIn(http)
        val repo = newRepo(http)
        val out = repo.estimate(43.7384, 7.4246, 43.7543, 7.4312, "monaco")
        assertTrue("got $out", out is EstimateOutcome.Online)
        out as EstimateOutcome.Online
        assertEquals("osrm", out.response.routeSource)
        assertEquals(18.82, out.response.estimates[0].lowTotal, 0.0)
        val cached = cache.routeFor(43.7384, 7.4246, 43.7543, 7.4312)
        assertNotNull("estimate must cache the route for offline reuse", cached)
        assertEquals(5234.5, cached!!.distanceMeters, 0.0)
    }

    @Test
    fun estimate_offline_fallsBackToCachedRouteThenStraightLine() = runTest {
        val http = client()
        signIn(http)
        val repo = newRepo(http)
        // Break ONLY the estimate endpoint (network down): bundle stays cached.
        responder = { method, path ->
            if (method == "POST" && path == "/v1/rides/estimate") {
                throw java.io.IOException("offline")
            }
            FakeResponse(500, """{"code":"http_500","message":"boom"}""")
        }
        val out = repo.estimate(43.7384, 7.4246, 43.7543, 7.4312, "monaco")
        assertTrue("got $out", out is EstimateOutcome.Offline)
        out as EstimateOutcome.Offline
        // No cached route yet for this pair? There IS one from the Online cache above
        // only if estimateOnline ran first - here none was saved, so straight-line.
        assertEquals("straight_line", out.result.routeSource)
        assertEquals(
            FareMath.haversineMeters(43.7384, 7.4246, 43.7543, 7.4312) * 1.3,
            out.result.distanceMeters, 0.0,
        )
        // Now seed a cached route for the SAME pair -> offline estimator reuses it.
        cache.saveRoute(
            CachedRoute(
                pickupLat = 43.7384, pickupLon = 7.4246,
                dropoffLat = 43.7543, dropoffLon = 7.4312,
                distanceMeters = 5234.5, durationSeconds = 720.0,
                cachedAtEpochSec = fixedClock.instant().epochSecond,
            ),
        )
        val out2 = repo.estimate(43.7384, 7.4246, 43.7543, 7.4312, "monaco")
        assertTrue(out2 is EstimateOutcome.Offline)
        assertEquals("cached_route", (out2 as EstimateOutcome.Offline).result.routeSource)
        val rows = out2.result.estimates
        assertEquals("standard", rows[0].rideType)
        assertEquals(18.82, rows[0].lowTotal, 0.0)
    }

    @Test
    fun cancel_flipsLocalStatusThroughTheServerMapping() = runTest {
        val http = client()
        signIn(http)
        val repo = newRepo(http)
        val rideId = repo.book(draft)
        repo.cancel(rideId).getOrThrow()
        val entity = db.rideDao().byId(rideId)!!
        assertEquals(statusFromServer("CANCELLED_BY_RIDER"), entity.status)
        assertEquals("CANCELLED", entity.status.name)
    }

    @Test
    fun refreshHistory_dedupesByServerIdAndInsertsUnknownRides() = runTest {
        val http = client()
        signIn(http)
        val repo = newRepo(http)
        val rideId = repo.book(draft)
        repo.refreshHistory()
        // srv-1 already known locally (backfilled) -> ONE row, same local id.
        val local = db.rideDao().byId(rideId)!!
        assertEquals("srv-1", local.serverId)
        // srv-2 unknown -> inserted under its server id.
        assertNotNull(db.rideDao().byId("srv-2"))
        assertEquals(statusFromServer("COMPLETED"), db.rideDao().byId("srv-2")!!.status)
    }
}

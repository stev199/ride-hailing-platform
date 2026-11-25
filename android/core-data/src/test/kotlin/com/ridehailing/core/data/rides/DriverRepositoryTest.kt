package com.ridehailing.core.data.rides

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ridehailing.core.data.network.ApiClientFactory
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
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
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P5 driver repository on plain JVM (MockEngine, AuthRepositoryTest pattern): duty
 * toggle, board poll, single-winner accept claim, trip actions - VERBATIM server
 * errors preserved (B-04) and auth_required surfaced before any network call.
 */
class DriverRepositoryTest {
    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: SessionStore

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
        val file = Files.createTempDirectory("driver-test").resolve("session.preferences_pb").toFile()
        sessions = SessionStore(
            PreferenceDataStoreFactory.create(scope = scope) { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.delete()
    }

    private fun repo(http: HttpClient): DriverRepository =
        DriverRepository(http, AuthRepository(http, sessions))

    private suspend fun signIn() {
        sessions.save(Session(accessToken = "tok-1", refreshToken = "r", userId = "d1", phone = null))
    }

    private val dutyDto = """{"online":true,"lat":43.7384,"lon":7.4246,"updatedAtEpochSec":1732540800}"""

    private fun rideDto(id: String, status: String): String =
        """{"id":"$id","status":"$status","city":"monaco","rideType":"standard","paymentMethod":"CASH",""" +
            """"note":null,"pickupLat":43.7384,"pickupLon":7.4246,"dropoffLat":43.7543,"dropoffLon":7.4312,""" +
            """"pickupName":"Harbor","dropoffName":"Airport","quote":{"amountMinor":1882,"currency":"EUR",""" +
            """"mode":"upfront_locked","meteredCapMinor":2823,"validUntilEpochSec":1732540890},"cancelFeeMinor":0,""" +
            """"createdAtEpochSec":1732540800,"updatedAtEpochSec":1732540800,"distanceMeters":123.5}"""

    @Test
    fun duty_goesOnline_andParsesTheAck() = runBlocking {
        signIn()
        responder = { method, path ->
            assertEquals("POST", method)
            assertEquals("/v1/driver/duty", path)
            FakeResponse(200, dutyDto)
        }
        val r = repo(client()).setDuty(online = true, lat = 43.7384, lon = 7.4246)
        assertTrue("got $r", r.isSuccess)
        assertEquals(true, r.getOrThrow().online)
    }

    @Test
    fun duty_offline_notSignedIn_failsTypedBeforeAnyNetwork() = runBlocking {
        val repo = repo(client())
        val r = repo.setDuty(online = true, lat = 1.0, lon = 1.0)
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull() as ApiRequestException
        assertEquals("auth_required", e.error.code)
    }

    @Test
    fun available_boardSurfacesVerbatimServerErrors() = runBlocking {
        signIn()
        responder = { method, path ->
            assertEquals("/v1/driver/rides/available", path)
            FakeResponse(409, """{"code":"duty_offline","message":"go online first"}""")
        }
        val r = repo(client()).availableRides()
        assertTrue(r.isFailure)
        assertEquals("duty_offline", (r.exceptionOrNull() as ApiRequestException).error.code)
    }

    @Test
    fun available_boardParsesRidesWithDistance() = runBlocking {
        signIn()
        responder = { method, path ->
            assertEquals("GET", method)
            FakeResponse(200, """{"rides":[${rideDto("srv-9", "REQUESTED")}],"limit":20,"offset":0}""")
        }
        val r = repo(client()).availableRides()
        assertTrue(r.isSuccess)
        val rides = r.getOrThrow()
        assertEquals(1, rides.size)
        assertEquals("srv-9", rides[0].id)
        assertEquals(123.5, rides[0].distanceMeters!!, 0.0)
        assertEquals(1882L, rides[0].quote.amountMinor)
    }

    @Test
    fun accept_andTripActions_mapToTheContract() = runBlocking {
        signIn()
        responder = { method, path ->
            when {
                method == "POST" && path == "/v1/rides/srv-9/accept" -> FakeResponse(200, rideDto("srv-9", "ACCEPTED"))
                method == "POST" && path == "/v1/rides/srv-9/arrive" -> FakeResponse(200, rideDto("srv-9", "ARRIVED"))
                method == "POST" && path == "/v1/rides/srv-9/start" -> FakeResponse(200, rideDto("srv-9", "IN_PROGRESS"))
                method == "POST" && path == "/v1/rides/srv-9/complete" -> FakeResponse(200, rideDto("srv-9", "COMPLETED"))
                else -> throw AssertionError("unexpected $method $path")
            }
        }
        val d = repo(client())
        assertEquals("ACCEPTED", d.accept("srv-9").getOrThrow().status)
        assertEquals("ARRIVED", d.tripAction("srv-9", DriverAction.ARRIVE).getOrThrow().status)
        assertEquals("IN_PROGRESS", d.tripAction("srv-9", DriverAction.START).getOrThrow().status)
        assertEquals("COMPLETED", d.tripAction("srv-9", DriverAction.COMPLETE).getOrThrow().status)
    }

    @Test
    fun accept_racingLoserGetsTheVerbatim409() = runBlocking {
        signIn()
        responder = { _, _ ->
            FakeResponse(409, """{"code":"already_accepted","message":"another driver won"}""")
        }
        val r = repo(client()).accept("srv-9")
        assertTrue(r.isFailure)
        assertEquals("already_accepted", (r.exceptionOrNull() as ApiRequestException).error.code)
    }
}

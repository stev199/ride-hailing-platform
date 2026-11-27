package com.ridehailing.backend.rides

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 admin live map routes (P6-4): TRIPS_READ-guarded snapshot of the duty
 * registry (driver positions) + the ACTIVE trips at their pickup coordinates.
 * Seeding follows the AdminTripsRoutesTest pattern: users through the user store,
 * rides through the PUBLIC booking API, duty through RideService.setDuty (the same
 * call DriverRoutes performs behind POST /v1/driver/duty).
 */
class AdminLiveRoutesTest {

    private fun withApp(graph: AuthGraph = AuthGraph(), block: suspend (HttpClient, AuthGraph) -> Unit) =
        testApplication {
            application { module(testProfile = false, auth = graph) }
            block(client, graph)
        }

    private fun createUserIn(graph: AuthGraph, phone: String, role: Role): User {
        val user = User(
            id = Ids.newId(),
            phone = phone, email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "User ${role.name}", role = role, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(user)
        return user
    }

    private val bookingBody = """
        {"pickupLat":43.7384,"pickupLon":7.4246,"dropoffLat":43.7543,"dropoffLon":7.4312,
         "city":"monaco","rideType":"standard","paymentMethod":"CASH"}
    """.trimIndent()

    private suspend fun HttpClient.getToken(identifier: String, password: String): String {
        val res = post("/v1/auth/login/password") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"identifier":"$identifier","password":"$password"}""")
        }
        val body = res.bodyAsText()
        check(res.status == HttpStatusCode.OK) { "login failed: ${res.status} $body" }
        return Json.parseToJsonElement(body).jsonObject["tokens"]!!
            .jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            header(HttpHeaders.ContentType, "application/json")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun HttpClient.authed(path: String, token: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.bookRide(token: String): String {
        val res = postJson("/v1/rides", bookingBody, token)
        val body = res.bodyAsText()
        check(res.status == HttpStatusCode.Created) { "booking failed: ${res.status} $body" }
        return Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `admin live - 401 anonymous, 403 rider, 200 admin with drivers and active trips`() =
        withApp { client, graph ->
            val rider = createUserIn(graph, "33631000021", Role.RIDER)
            val riderToken = client.getToken("33631000021", "password-1")
            val driver = createUserIn(graph, "33631000022", Role.DRIVER)
            createUserIn(graph, "33631000029", Role.ADMIN_SUPPORT)
            val adminToken = client.getToken("33631000029", "password-1")

            // Duty through the SAME service call the driver route makes.
            val duty = graph.rideService.setDuty(driver, online = true, lat = 43.7384, lon = 7.4246)
            check(duty is com.ridehailing.backend.rides.RideService.DutyResult.Set)

            val ride = client.bookRide(riderToken)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/live").status)
            val forbidden = client.authed("/v1/admin/live", riderToken)
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)

            val live = client.authed("/v1/admin/live", adminToken)
            assertEquals(HttpStatusCode.OK, live.status, live.bodyAsText())
            val body = live.json()

            val drivers = body["drivers"]!!.jsonArray
            assertEquals(1, drivers.size, "exactly the on-duty driver")
            val d0 = drivers[0].jsonObject
            assertEquals(driver.id, d0["driverId"]!!.jsonPrimitive.content)
            assertEquals(43.7384, d0["lat"]!!.jsonPrimitive.content.toDouble())
            assertEquals(7.4246, d0["lon"]!!.jsonPrimitive.content.toDouble())
            assertEquals(true, d0["online"]!!.jsonPrimitive.content.toBooleanStrict())
            assertTrue(d0.containsKey("updatedAtEpochSec"))

            val trips = body["trips"]!!.jsonArray
            assertEquals(1, trips.size, "the REQUESTED booking is active")
            val t0 = trips[0].jsonObject
            assertEquals(ride, t0["id"]!!.jsonPrimitive.content)
            assertEquals("REQUESTED", t0["status"]!!.jsonPrimitive.content)
            assertEquals(43.7384, t0["pickupLat"]!!.jsonPrimitive.content.toDouble())
            assertEquals(7.4246, t0["pickupLon"]!!.jsonPrimitive.content.toDouble())
            assertEquals("online", t0["origin"]!!.jsonPrimitive.content)
            assertTrue(t0["driverId"] is kotlinx.serialization.json.JsonNull)
            assertEquals(rider.id, t0["riderId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `admin live - completed rides and duty-off drivers leave the snapshot`() =
        withApp { client, graph ->
            val rider = createUserIn(graph, "33631000031", Role.RIDER)
            val riderToken = client.getToken("33631000031", "password-1")
            val driver = createUserIn(graph, "33631000032", Role.DRIVER)
            createUserIn(graph, "33631000039", Role.ADMIN_SUPPORT)
            val adminToken = client.getToken("33631000039", "password-1")

            client.bookRide(riderToken)
            // Duty ON then OFF: the registry removes offline drivers entirely.
            graph.rideService.setDuty(driver, online = true, lat = 43.7, lon = 7.4)
            graph.rideService.setDuty(driver, online = false, lat = 43.7, lon = 7.4)

            val live = client.authed("/v1/admin/live", adminToken)
            assertEquals(HttpStatusCode.OK, live.status)
            val body = live.json()
            assertEquals(0, body["drivers"]!!.jsonArray.size, "duty-off driver is gone")
            assertEquals(1, body["trips"]!!.jsonArray.size, "REQUESTED stays on the map")

            // Cancel the ride -> it leaves the live map (CANCELLED_BY_RIDER is historical).
            val rideId = client.authed("/v1/rides?limit=10", riderToken)
                .json()["rides"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
            val cancel = client.postJson("/v1/rides/$rideId/cancel", "{}", riderToken)
            assertEquals(HttpStatusCode.OK, cancel.status, cancel.bodyAsText())

            val after = client.authed("/v1/admin/live", adminToken).json()
            assertEquals(0, after["trips"]!!.jsonArray.size, "cancelled ride is off the live map")
        }
}

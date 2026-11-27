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
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 admin trips routes (round-20): TRIPS_READ-guarded list (newest first,
 * status/city filters, offset pagination) + detail with the ride.* timeline from
 * the hash-chained event store. Rides are seeded through the PUBLIC booking API
 * (register -> login -> POST /v1/rides) so the test exercises the same path as
 * production (AdminRoutesTest pattern for the admin user seeding).
 */
class AdminTripsRoutesTest {

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
    fun `admin trips list - 401 anonymous, 403 rider, 200 admin with filters and pagination`() =
        withApp { client, graph ->
            val rider = createUserIn(graph, "33631000001", Role.RIDER)
            val riderToken = client.getToken("33631000001", "password-1")
            createUserIn(graph, "33631000009", Role.ADMIN_SUPPORT)
            val adminToken = client.getToken("33631000009", "password-1")

            val rideA = client.bookRide(riderToken)
            val rideB = client.bookRide(riderToken)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/rides").status)
            val forbidden = client.authed("/v1/admin/rides", riderToken)
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)

            val list = client.authed("/v1/admin/rides", adminToken)
            assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
            val rides = list.json()["rides"]!!.jsonArray
            assertEquals(2, rides.size)
            // Newest first (same-second inserts - the seq tiebreak decides deterministically).
            assertEquals(rideB, rides[0].jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(rideA, rides[1].jsonObject["id"]!!.jsonPrimitive.content)
            // Admin DTO carries actor ids + the origin field (online until Phase 10 mesh).
            assertEquals(rider.id, rides[0].jsonObject["riderId"]!!.jsonPrimitive.content)
            // Explicit JSON null (kotlinx serializes nullable props as null, not absent).
            assertTrue(rides[0].jsonObject["driverId"] is kotlinx.serialization.json.JsonNull)
            assertEquals("online", rides[0].jsonObject["origin"]!!.jsonPrimitive.content)
            assertEquals("REQUESTED", rides[0].jsonObject["status"]!!.jsonPrimitive.content)

            // Filters.
            val filtered = client.authed("/v1/admin/rides?status=REQUESTED&city=monaco", adminToken)
            assertEquals(2, filtered.json()["rides"]!!.jsonArray.size)
            val empty = client.authed("/v1/admin/rides?city=lyons", adminToken)
            assertEquals(0, empty.json()["rides"]!!.jsonArray.size)
            val paged = client.authed("/v1/admin/rides?limit=1", adminToken)
            assertEquals(1, paged.json()["rides"]!!.jsonArray.size)
            assertEquals(1, paged.json()["limit"]!!.jsonPrimitive.content.toInt())

            // Contract violations.
            assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/rides?status=NOPE", adminToken).status)
            assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/rides?limit=0", adminToken).status)

            // READ_ONLY role has TRIPS_READ too (READ_ONLY set).
            createUserIn(graph, "33631000008", Role.ADMIN_READONLY)
            val readonlyToken = client.getToken("33631000008", "password-1")
            assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/rides", readonlyToken).status)
        }

    @Test
    fun `admin trip detail - 200 with ride dot timeline, 404 unknown`() = withApp { client, graph ->
        createUserIn(graph, "33631000002", Role.RIDER)
        val riderToken = client.getToken("33631000002", "password-1")
        createUserIn(graph, "33631000009", Role.ADMIN_SUPPORT)
        val adminToken = client.getToken("33631000009", "password-1")

        val rideId = client.bookRide(riderToken)

        val detail = client.authed("/v1/admin/rides/$rideId", adminToken)
        assertEquals(HttpStatusCode.OK, detail.status, detail.bodyAsText())
        val body = detail.json()
        assertEquals(rideId, body["ride"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val timeline = body["timeline"]!!.jsonArray
        assertTrue(timeline.isNotEmpty(), "timeline must contain at least ride.requested")
        assertEquals("ride.requested", timeline[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            timeline[0].jsonObject["payloadJson"]!!.jsonPrimitive.content.contains(rideId),
            "timeline event payload carries the rideId",
        )

        // Unknown id: no existence leak pattern (404, same as rider-owned reads).
        val missing = client.authed("/v1/admin/rides/${UUID.randomUUID()}", adminToken)
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }
}

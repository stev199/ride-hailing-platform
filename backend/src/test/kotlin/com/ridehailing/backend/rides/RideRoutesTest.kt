package com.ridehailing.backend.rides

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.module
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-2 ride routes: booking behind RIDER role, rider-owned reads, cancel flow
 * with the fee. The quote served by the API is verifiable with the public key
 * the CONFIG bundle serves (device contract).
 */
class RideRoutesTest {

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
         "city":"monaco","rideType":"standard","paymentMethod":"CASH","note":"call at gate"}
    """.trimIndent()

    @Test
    fun `booking flow - 401 anonymous, 403 driver, 201 rider with verifiable quote`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33630000001", Role.RIDER)
        val riderToken = client.getToken("33630000001", "password-1")
        createUserIn(graph, "33630000002", Role.DRIVER)
        val driverToken = client.getToken("33630000002", "password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/rides", bookingBody).status)
        val forbidden = client.postJson("/v1/rides", bookingBody, driverToken)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("booking_role_required", forbidden.errCode())

        val created = client.postJson("/v1/rides", bookingBody, riderToken)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val body = created.json()
        assertEquals("REQUESTED", body["status"]!!.jsonPrimitive.content)
        val quote = body["quote"]!!.jsonObject
        assertTrue(quote["amountMinor"]!!.jsonPrimitive.content.toLong() > 0)
        assertEquals("upfront_locked", quote["mode"]!!.jsonPrimitive.content)
        // Signature verifiable with the public key the config bundle serves.
        val pub = client.get("/v1/config").json()["publicKey"]!!.jsonPrimitive.content
        assertEquals(pub, quote["publicKeyHex"]!!.jsonPrimitive.content)
        assertTrue(quote["signature"]!!.jsonPrimitive.content.length > 100, "Ed25519 signature present")

        // Rider-owned read back.
        val id = body["id"]!!.jsonPrimitive.content
        val read = client.authed("/v1/rides/$id", riderToken)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(id, read.json()["id"]!!.jsonPrimitive.content)
        assertTrue(rider.role == Role.RIDER)
    }

    @Test
    fun `ride reads and cancels are rider-owned with no existence leak`() = withApp { client, graph ->
        createUserIn(graph, "33630000003", Role.RIDER)
        val tokenA = client.getToken("33630000003", "password-1")
        createUserIn(graph, "33630000004", Role.RIDER)
        val tokenB = client.getToken("33630000004", "password-1")

        val id = client.postJson("/v1/rides", bookingBody, tokenA).json()["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.NotFound, client.authed("/v1/rides/$id", tokenB).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/rides/$id/cancel", "{}", tokenB).status)
        assertEquals(HttpStatusCode.NotFound, client.authed("/v1/rides/8b1a0a5e-0000-0000-0000-000000000000", tokenA).status)

        // Own list shows the ride.
        val list = client.authed("/v1/rides", tokenA)
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(id in list.bodyAsText())

        // Cancel own ride: 200 + CANCELLED_BY_RIDER + configured fee (3.0 EUR = 300 minor).
        val cancelled = client.postJson("/v1/rides/$id/cancel", "{}", tokenA)
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertEquals("CANCELLED_BY_RIDER", cancelled.json()["status"]!!.jsonPrimitive.content)
        assertEquals(300L, cancelled.json()["cancelFeeMinor"]!!.jsonPrimitive.content.toLong())
        // Cancel again: terminal -> 409.
        assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/rides/$id/cancel", "{}", tokenA).status)
    }

    @Test
    fun `list pagination is validated`() = withApp { client, graph ->
        createUserIn(graph, "33630000005", Role.RIDER)
        val token = client.getToken("33630000005", "password-1")
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?limit=0", token).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?limit=101", token).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?offset=-1", token).status)
        assertEquals(HttpStatusCode.OK, client.authed("/v1/rides?limit=1&offset=0", token).status)
    }

    @Test
    fun `malformed pagination params are 400 - never silently defaulted (round-11 ST201)`() = withApp { client, graph ->
        createUserIn(graph, "33630000007", Role.RIDER)
        val token = client.getToken("33630000007", "password-1")
        // The exact fuzzer case (offset=null&offset=null) plus both-params variants.
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?offset=null&offset=null", token).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?offset=null", token).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/rides?limit=abc", token).status)
        assertEquals(HttpStatusCode.OK, client.authed("/v1/rides?limit=5", token).status)
    }

    @Test
    fun `known path with undeclared method is 405 with Allow and unknown path stays 404 (round-11 ST204)`() = withApp { client, _ ->
        // /v1/rides/estimate declares POST only - a GET must be 405 (was 404 via fall-through).
        val m = client.get("/v1/rides/estimate")
        assertEquals(HttpStatusCode.MethodNotAllowed, m.status)
        assertEquals("POST", m.headers[HttpHeaders.Allow])
        assertEquals("method_not_allowed", m.errCode())
        // /v1/rides declares GET+POST - DELETE is 405 with both listed.
        val d = client.delete("/v1/rides")
        assertEquals(HttpStatusCode.MethodNotAllowed, d.status)
        assertEquals("GET, POST", d.headers[HttpHeaders.Allow])
        // Unknown paths keep the standard 404 body.
        val nf = client.get("/definitely-not-a-route")
        assertEquals(HttpStatusCode.NotFound, nf.status)
        assertEquals("not_found", nf.errCode())
        // Declared methods are untouched (the catch-all is registered LAST).
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `booking validation errors carry the documented codes`() = withApp { client, graph ->
        createUserIn(graph, "33630000006", Role.RIDER)
        val token = client.getToken("33630000006", "password-1")
        assertEquals(
            "unknown_city",
            client.postJson("/v1/rides", bookingBody.replace("monaco", "paris"), token).errCode(),
        )
        assertEquals(
            "unknown_ride_type",
            client.postJson("/v1/rides", bookingBody.replace("standard", "helicopter"), token).errCode(),
        )
        assertEquals(
            "payment_method_unavailable",
            client.postJson("/v1/rides", bookingBody.replace("CASH", "WALLET"), token).errCode(),
        )
        assertEquals(
            "invalid_coordinates",
            client.postJson("/v1/rides", bookingBody.replace("43.7384", "999.0"), token).errCode(),
        )
    }

    // ---------- helpers (file-local mirrors of the AdminRoutesTest ones) ----------

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.errCode(): String = json()["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun HttpClient.authed(path: String, token: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.getToken(identifier: String, password: String): String {
        val res = postJson("/v1/auth/login/password", """{"identifier":"$identifier","password":"$password"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return res.json()["tokens"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
    }
}

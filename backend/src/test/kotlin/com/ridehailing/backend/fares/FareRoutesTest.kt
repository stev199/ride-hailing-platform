package com.ridehailing.backend.fares

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4-1 fare routes: estimate behind auth (any active user), admin fares
 * read/patch behind CONFIG_READ/CONFIG_WRITE, D75 full-replace validation,
 * signed bundle version bump + audit, straight-line degraded estimate (no geo
 * stack on the test host -> connection refused -> fallback contract).
 */
class FareRoutesTest {

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

    private val estimateBody = """
        {"pickupLat":43.7384,"pickupLon":7.4246,"dropoffLat":43.7543,"dropoffLon":7.4312,"city":"monaco","rideType":"standard"}
    """.trimIndent()

    private val patchBody = """
        {"straightLine":{"detourFactor":1.4,"assumedAvgSpeedKmh":24.0},
         "quote":{"validitySeconds":90,"mode":"upfront_locked","meteredCapMultiplier":1.5},
         "cities":{"monaco":{"currency":"EUR","rideTypes":{
            "standard":{"base":2.5,"perKm":1.8,"perMinute":0.45,"minimumFare":8.0,
                        "bookingFee":1.5,"cancelFee":3.0,"waitFeePerMinute":0.5,
                        "night":{"multiplier":1.25,"startHourUtc":22,"endHourUtc":6},
                        "peak":{"multiplier":1.0,"startHourUtc":0,"endHourUtc":0}}}}}}
    """.trimIndent()

    @Test
    fun `estimate is 401 anonymous and works for any authenticated role`() = withApp { client, graph ->
        createUserIn(graph, "33620000001", Role.RIDER)
        createUserIn(graph, "33620000002", Role.DRIVER)
        val riderToken = client.getToken("33620000001", "password-1")
        val driverToken = client.getToken("33620000002", "password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/rides/estimate", estimateBody).status)

        for (token in listOf(riderToken, driverToken)) {
            val res = client.postJson("/v1/rides/estimate", estimateBody, token)
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            val body = res.json()
            assertEquals("monaco", body["city"]!!.jsonPrimitive.content)
            // No geo stack on the test host: OSRM connection refused -> degraded fallback.
            assertEquals("straight_line", body["routeSource"]!!.jsonPrimitive.content)
            val est = body["estimates"]!!.jsonArray.joinToString()
            assertTrue("standard" in est, "standard ride type present: $est")
            assertTrue(body["distanceMeters"]!!.jsonPrimitive.content.toDouble() > 0.0)
            assertEquals("estimated_price_not_final", body["disclaimer"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `estimate invalid inputs are 400 with documented codes`() = withApp { client, graph ->
        createUserIn(graph, "33620000003", Role.RIDER)
        val token = client.getToken("33620000003", "password-1")
        val badCoords = """
            {"pickupLat":999.0,"pickupLon":7.42,"dropoffLat":43.75,"dropoffLon":7.43,"city":"monaco"}
        """.trimIndent()
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/rides/estimate", badCoords, token).status)
        assertEquals("invalid_coordinates", client.postJson("/v1/rides/estimate", badCoords, token).errCode())
        assertEquals(
            "unknown_city",
            client.postJson(
                "/v1/rides/estimate",
                estimateBody.replace("\"monaco\"", "\"paris\""),
                token,
            ).errCode(),
        )
        assertEquals(
            "unknown_ride_type",
            client.postJson(
                "/v1/rides/estimate",
                estimateBody.replace("\"standard\"", "\"helicopter\""),
                token,
            ).errCode(),
        )
    }

    @Test
    fun `admin fares - 401 anonymous, 403 rider, GET+PATCH ops, invalid patch 400 no version burn`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33620000004", Role.RIDER)
        val riderToken = client.getToken("33620000004", "password-1")
        createUserIn(graph, "33620000005", Role.ADMIN_OPS)
        val opsToken = client.getToken("33620000005", "password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/config/fares").status)
        assertEquals(HttpStatusCode.Forbidden, client.authed("/v1/admin/config/fares", riderToken).status)
        assertEquals("forbidden", client.authed("/v1/admin/config/fares", riderToken).errCode())

        val versionBefore = client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong()
        val patched = client.patchJson("/v1/admin/config/fares", patchBody, opsToken)
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        val versionAfter = client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong()
        assertEquals(versionBefore + 1, versionAfter, "accepted patch bumps the signed bundle version")
        val getRes = client.authed("/v1/admin/config/fares", opsToken)
        assertEquals(HttpStatusCode.OK, getRes.status)
        assertTrue("\"detourFactor\":1.4" in getRes.bodyAsText(), getRes.bodyAsText())

        val invalid = patchBody.replace("\"currency\":\"EUR\"", "\"currency\":\"euro\"")
        val badPatch = client.patchJson("/v1/admin/config/fares", invalid, opsToken)
        assertEquals(HttpStatusCode.BadRequest, badPatch.status)
        assertEquals("invalid_fares", badPatch.errCode())
        assertEquals(versionAfter, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `partial full-replace patches are 400 - defaults never silently fill in (round-11 ST201)`() = withApp { client, graph ->
        createUserIn(graph, "33620000006", Role.ADMIN_OPS)
        val opsToken = client.getToken("33620000006", "password-1")

        // The exact fuzzer case: cities-only body missing straightLine+quote.
        val citiesOnly = """{"cities":{"monaco":{"currency":"EUR","rideTypes":{
            "standard":{"base":2.5,"perKm":1.8,"perMinute":0.45,"minimumFare":8.0}}}}}"""
        val noQuote = """
            {"straightLine":{"detourFactor":1.4,"assumedAvgSpeedKmh":24.0},
             "cities":{"monaco":{"currency":"EUR","rideTypes":{
                "standard":{"base":2.5,"perKm":1.8,"perMinute":0.45,"minimumFare":8.0}}}}}
        """.trimIndent()
        val partialSurge = """
            {"straightLine":{"detourFactor":1.4,"assumedAvgSpeedKmh":24.0},
             "quote":{"validitySeconds":90,"mode":"upfront_locked","meteredCapMultiplier":1.5},
             "cities":{"monaco":{"currency":"EUR","rideTypes":{
                "standard":{"base":2.5,"perKm":1.8,"perMinute":0.45,"minimumFare":8.0,
                            "night":{"multiplier":1.25}}}}}}
        """.trimIndent()
        for (body in listOf(citiesOnly, noQuote, partialSurge)) {
            val res = client.patchJson("/v1/admin/config/fares", body, opsToken)
            assertEquals(HttpStatusCode.BadRequest, res.status, "body: $body -> ${res.bodyAsText()}")
            assertEquals("invalid_fares", res.errCode())
        }
        // Full document still passes.
        assertEquals(HttpStatusCode.OK, client.patchJson("/v1/admin/config/fares", patchBody, opsToken).status)
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

    private suspend fun HttpClient.patchJson(path: String, body: String, token: String? = null): HttpResponse =
        patch(path) {
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

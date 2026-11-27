package com.ridehailing.backend

import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthAndRoutesTest {
    private fun withApp(testProfile: Boolean, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module(testProfile = testProfile) }
            block()
        }

    @Test
    fun `health returns ok with version`() = withApp(testProfile = false) {
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals(BACKEND_VERSION, body["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `test routes are PRESENT when test profile is on`() = withApp(testProfile = true) {
        val barrier: HttpResponse = client.get("/test/barrier/e2e-start")
        assertEquals(HttpStatusCode.NoContent, barrier.status)
        val seed: HttpResponse = client.post("/test/seed") {
            contentType(ContentType.Application.Json)
            setBody("""{"namespace":"rider-0"}""")
        }
        assertEquals(HttpStatusCode.OK, seed.status)
        assertTrue(seed.bodyAsText().contains("rider-0"))
    }

    @Test
    fun `test routes are ABSENT when test profile is off (release builds)`() = withApp(testProfile = false) {
        val barrier = client.get("/test/barrier/e2e-start")
        assertEquals(HttpStatusCode.NotFound, barrier.status)
        val seed = client.post("/test/seed") {
            contentType(ContentType.Application.Json)
            setBody("""{"namespace":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, seed.status)
        // 404 body conforms to the documented Error schema
        val err = Json.parseToJsonElement(barrier.bodyAsText()).jsonObject
        assertEquals("not_found", err["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `405 responses carry RFC 9110 Allow header`() = withApp(testProfile = false) {
        val res = client.post("/health")
        assertEquals(HttpStatusCode.MethodNotAllowed, res.status)
        val allow = res.headers["Allow"]
        assertEquals("GET", allow)
    }

    @Test
    fun `405 Allow header is EXACTLY once and matches the resource methods (round-23 ST205)`() =
        withApp(testProfile = false) {
            // POST /health: schema documents GET only -> single "GET".
            val res = client.post("/health")
            assertEquals(HttpStatusCode.MethodNotAllowed, res.status)
            assertEquals(listOf("GET"), res.headers.getAll("Allow"))

            // /v1/rides (GET+POST) hit with PATCH: exactly one Allow = "GET, POST".
            val rides = client.patch("/v1/rides")
            assertEquals(HttpStatusCode.MethodNotAllowed, rides.status)
            assertEquals(listOf("GET, POST"), rides.headers.getAll("Allow"))

            // P6-1 verification collection: OPTIONS -> exactly one Allow = "POST"
            // (the fuzzer saw "GET, POST, GET, POST" - a wrong list, twice).
            val ver = client.options("/v1/verification")
            assertEquals(HttpStatusCode.MethodNotAllowed, ver.status)
            assertEquals(listOf("POST"), ver.headers.getAll("Allow"))
        }

    @Test
    fun `known verification paths answer 405 + Allow for undeclared methods (round-23 ST204)`() =
        withApp(testProfile = false) {
            // TRACE on GET-only paths must be 405 + Allow "GET" (was: 404 not_found).
            for (path in listOf("/v1/verification/me", "/v1/admin/verification")) {
                val res = client.request(path) { method = HttpMethod("TRACE") }
                assertEquals(HttpStatusCode.MethodNotAllowed, res.status, path)
                assertEquals(listOf("GET"), res.headers.getAll("Allow"), path)
            }
            // POST-only submit path hit with GET -> 405 + Allow "POST".
            val submit = client.get("/v1/verification")
            assertEquals(HttpStatusCode.MethodNotAllowed, submit.status)
            assertEquals(listOf("POST"), submit.headers.getAll("Allow"))
        }

    @Test
    fun `unknown route returns documented 404 error shape`() = withApp(testProfile = false) {
        val res = client.get("/v1/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, res.status)
        val err = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("not_found", err["code"]!!.jsonPrimitive.content)
    }
}

package com.ridehailing.backend.verification

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
 * Verification center routes (P6-1): driver surface (POST /v1/verification,
 * GET /v1/verification/me) is authentication-gated with the DRIVER check in the
 * service; admin surface (/v1/admin/verification*) is VERIFICATION_READ/WRITE
 * RBAC-gated. Admin seeding follows AdminTripsRoutesTest.
 */
class VerificationRoutesTest {

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

    private val submitBody = """
        {"licenseNumber":"FR-12345678","vehiclePlate":"AA-123-BB",
         "declaredDocs":["license_front","insurance"]}
    """.trimIndent()

    @Test
    fun `driver submits - 201, me 200, rider 403 role_not_driver, anonymous 401`() = withApp { client, graph ->
        val driver = createUserIn(graph, "33631000110", Role.DRIVER)
        val driverToken = client.getToken("33631000110", "password-1")
        val rider = createUserIn(graph, "33631000111", Role.RIDER)
        val riderToken = client.getToken("33631000111", "password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/verification", submitBody).status)

        val forbidden = client.postJson("/v1/verification", submitBody, riderToken)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("role_not_driver", forbidden.json()["code"]!!.jsonPrimitive.content)

        val created = client.postJson("/v1/verification", submitBody, driverToken)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val submission = created.json()
        assertEquals(driver.id, submission["userId"]!!.jsonPrimitive.content)
        assertEquals("PENDING", submission["status"]!!.jsonPrimitive.content)
        assertEquals("FR-12345678", submission["licenseNumber"]!!.jsonPrimitive.content)
        assertEquals(2, submission["declaredDocs"]!!.jsonArray.size)

        val me = client.authed("/v1/verification/me", driverToken)
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(submission["id"]!!.jsonPrimitive.content, me.json()["id"]!!.jsonPrimitive.content)

        // Second submit while pending -> 409 (D-style explicit code).
        assertEquals(
            HttpStatusCode.Conflict,
            client.postJson("/v1/verification", submitBody, driverToken).status,
        )
        assertEquals(
            "already_submitted",
            client.postJson("/v1/verification", submitBody, driverToken).json()["code"]!!.jsonPrimitive.content,
        )

        // A fresh rider has no submission -> 404 no_submission.
        assertEquals(HttpStatusCode.NotFound, client.authed("/v1/verification/me", riderToken).status)
    }

    @Test
    fun `submit validation - blank and oversized fields are 400 invalid_verification`() = withApp { client, graph ->
        createUserIn(graph, "33631000112", Role.DRIVER)
        val token = client.getToken("33631000112", "password-1")
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/v1/verification", """{"licenseNumber":" ","vehiclePlate":"AA-1","declaredDocs":["a"]}""", token).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/v1/verification", """{"licenseNumber":"L","vehiclePlate":"","declaredDocs":["a"]}""", token).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/v1/verification", """{"licenseNumber":"L","vehiclePlate":"P","declaredDocs":[]}""", token).status,
        )
    }

    @Test
    fun `admin queue - 401 anonymous, 403 rider, 200 verifier with filters and pagination`() = withApp { client, graph ->
        val driver = createUserIn(graph, "33631000113", Role.DRIVER)
        val driverToken = client.getToken("33631000113", "password-1")
        createUserIn(graph, "33631000114", Role.ADMIN_VERIFIER)
        val verifierToken = client.getToken("33631000114", "password-1")
        val rider = createUserIn(graph, "33631000115", Role.RIDER)
        val riderToken = client.getToken("33631000115", "password-1")

        client.postJson("/v1/verification", submitBody, driverToken)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/verification").status)
        assertEquals(HttpStatusCode.Forbidden, client.authed("/v1/admin/verification", riderToken).status)

        val queue = client.authed("/v1/admin/verification", verifierToken)
        assertEquals(HttpStatusCode.OK, queue.status, queue.bodyAsText())
        assertEquals(1, queue.json()["submissions"]!!.jsonArray.size)

        val filtered = client.authed("/v1/admin/verification?status=PENDING", verifierToken)
        assertEquals(1, filtered.json()["submissions"]!!.jsonArray.size)
        val empty = client.authed("/v1/admin/verification?status=APPROVED", verifierToken)
        assertEquals(0, empty.json()["submissions"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/verification?status=NOPE", verifierToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/verification?limit=0", verifierToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/verification?limit=201", verifierToken).status)

        // READ_ONLY can see the queue but has no VERIFICATION_WRITE.
        createUserIn(graph, "33631000116", Role.ADMIN_READONLY)
        val readonlyToken = client.getToken("33631000116", "password-1")
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/verification", readonlyToken).status)
    }

    @Test
    fun `approve and reject - verifier allowed, readonly write 403, double-review 409, reject needs reason`() = withApp { client, graph ->
        val driverA = createUserIn(graph, "33631000117", Role.DRIVER)
        val driverB = createUserIn(graph, "33631000118", Role.DRIVER)
        val tokenA = client.getToken("33631000117", "password-1")
        val tokenB = client.getToken("33631000118", "password-1")
        val verifier = createUserIn(graph, "33631000119", Role.ADMIN_VERIFIER)
        val verifierToken = client.getToken("33631000119", "password-1")
        createUserIn(graph, "33631000120", Role.ADMIN_READONLY)
        val readonlyToken = client.getToken("33631000120", "password-1")

        val idA = client.postJson("/v1/verification", submitBody, tokenA).json()["id"]!!.jsonPrimitive.content
        val idB = client.postJson("/v1/verification", submitBody, tokenB).json()["id"]!!.jsonPrimitive.content

        // READ_ONLY has VERIFICATION_READ (queue 200) but NOT VERIFICATION_WRITE.
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/v1/admin/verification/$idA/approve", "{}", readonlyToken).status)

        // Reject requires a reason (1..512).
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/v1/admin/verification/$idA/reject", """{"reason":"  "}""", verifierToken).status,
        )
        val rejected = client.postJson(
            "/v1/admin/verification/$idA/reject",
            """{"reason":"license unreadable"}""",
            verifierToken,
        )
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertEquals("REJECTED", rejected.json()["status"]!!.jsonPrimitive.content)
        assertEquals("license unreadable", rejected.json()["rejectReason"]!!.jsonPrimitive.content)

        // Double review -> 409 not_pending (state machine guard).
        assertEquals(
            HttpStatusCode.Conflict,
            client.postJson("/v1/admin/verification/$idA/approve", "{}", verifierToken).status,
        )
        assertEquals(
            "not_pending",
            client.postJson("/v1/admin/verification/$idA/approve", "{}", verifierToken).json()["code"]!!.jsonPrimitive.content,
        )

        val approved = client.postJson("/v1/admin/verification/$idB/approve", "{}", verifierToken)
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        assertEquals("APPROVED", approved.json()["status"]!!.jsonPrimitive.content)
        assertEquals(verifier.id, approved.json()["reviewerId"]!!.jsonPrimitive.content)

        // Unknown id -> 404; detail read works; driver sees the reject verbatim on /me.
        assertEquals(
            HttpStatusCode.NotFound,
            client.postJson("/v1/admin/verification/${UUID.randomUUID()}/approve", "{}", verifierToken).status,
        )
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/verification/$idA", verifierToken).status)
        val meA = client.authed("/v1/verification/me", tokenA)
        assertEquals("REJECTED", meA.json()["status"]!!.jsonPrimitive.content)
        assertEquals("license unreadable", meA.json()["rejectReason"]!!.jsonPrimitive.content)
    }
}

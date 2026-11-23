package com.ridehailing.backend.auth

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.module
import com.ridehailing.backend.auth.UserStatus
import io.ktor.client.HttpClient
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthRoutesTest {
    private fun withApp(graph: AuthGraph = AuthGraph(), testProfile: Boolean = false, block: suspend (HttpClient, AuthGraph) -> Unit) =
        testApplication {
            application { module(testProfile = testProfile, auth = graph) }
            block(client, graph)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.errCode(): String = json()["code"]!!.jsonPrimitive.content

    private suspend fun HttpClient.register(phone: String, password: String, displayName: String = "Rider One"): HttpResponse =
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"$displayName","password":"$password","phone":"$phone"}""")
        }

    private suspend fun HttpClient.post(path: String, body: String): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `register issues tokens and me endpoint resolves the user`() = withApp { client, _ ->
        val res = client.register("33600000001", "a-reasonable-pass-1")
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.json()
        val user = body["user"]!!.jsonObject
        assertEquals("RIDER", user["role"]!!.jsonPrimitive.content)
        assertEquals("33600000001", user["phone"]!!.jsonPrimitive.content)
        val tokens = body["tokens"]!!.jsonObject
        val access = tokens["accessToken"]!!.jsonPrimitive.content
        assertTrue(access.split('.').size == 3, "JWT has 3 segments")
        val me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer $access") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Rider One", me.json()["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `register validates input and rejects duplicates`() = withApp { client, _ ->
        assertEquals(HttpStatusCode.BadRequest, client.register("bad", "a-reasonable-pass-1").status, "invalid phone")
        assertEquals(HttpStatusCode.BadRequest, client.register("33600000002", "short").status, "short password")
        assertEquals(HttpStatusCode.Created, client.register("33600000002", "a-reasonable-pass-2").status)
        val dup = client.register("33600000002", "another-pass-2026")
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("identifier_taken", dup.errCode())
    }

    @Test
    fun `password login returns uniform error for unknown user and wrong password`() = withApp { client, _ ->
        assertEquals(HttpStatusCode.Created, client.register("33600000003", "a-reasonable-pass-3").status)
        val wrongPw = client.post("/v1/auth/login/password", """{"identifier":"33600000003","password":"not-the-password-9"}""")
        val unknown = client.post("/v1/auth/login/password", """{"identifier":"33999999999","password":"whatever-pass-9"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrongPw.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("invalid_credentials", wrongPw.errCode())
        assertEquals(wrongPw.errCode(), unknown.errCode(), "no user-enumeration oracle")
        val ok = client.post("/v1/auth/login/password", """{"identifier":"+33 600 000 003","password":"a-reasonable-pass-3"}""")
        assertEquals(HttpStatusCode.OK, ok.status, "normalization accepts formatted phone")
    }

    @Test
    fun `banned accounts cannot log in`() = withApp { client, graph ->
        val banned = User(
            id = "banned-1", phone = "33600000004", email = null,
            passwordHash = graph.passwords.hash("a-reasonable-pass-4"),
            displayName = "Banned", role = Role.RIDER, status = UserStatus.BANNED_PERM,
            createdAtEpochSec = 0,
        )
        graph.users.insert(banned)
        val res = client.post("/v1/auth/login/password", """{"identifier":"33600000004","password":"a-reasonable-pass-4"}""")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("account_banned", res.errCode())
    }

    @Test
    fun `otp flow end to end via dev inbox (test profile)`() = withApp(testProfile = true) { client, _ ->
        val issued = client.post("/v1/auth/otp/request", """{"channel":"sms","target":"33600000005"}""")
        assertEquals(HttpStatusCode.OK, issued.status)
        val requestId = issued.json()["requestId"]!!.jsonPrimitive.content
        assertNotEquals("", requestId)
        val inboxRes = client.get("/test/otp/latest?target=33600000005")
        assertEquals(HttpStatusCode.OK, inboxRes.status)
        val code = Json.parseToJsonElement(inboxRes.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val verify = client.post(
            "/v1/auth/otp/verify",
            """{"requestId":"$requestId","code":"$code","displayName":"Otp Rider"}""",
        )
        assertEquals(HttpStatusCode.OK, verify.status)
        val body = verify.json()
        assertEquals("true", body["created"]!!.jsonPrimitive.content)
        assertEquals("Otp Rider", body["user"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        // Access token from OTP login works on /v1/me.
        val access = body["tokens"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
        val me = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer $access") }
        assertEquals(HttpStatusCode.OK, me.status)
        // Resend inside the 45 s window is refused.
        val issued2 = client.post("/v1/auth/otp/request", """{"channel":"sms","target":"33600000005"}""")
        assertEquals(HttpStatusCode.TooManyRequests, issued2.status)
        assertEquals("resend_too_soon", issued2.errCode())
    }

    @Test
    fun `otp unknown channel and disabled channel are refused`() = withApp { client, _ ->
        val badChannel = client.post("/v1/auth/otp/request", """{"channel":"carrier-pigeon","target":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, badChannel.status)
        // email channel is disabled by default config
        val email = client.post("/v1/auth/otp/request", """{"channel":"email","target":"rider@example.invalid"}""")
        assertEquals(HttpStatusCode.Forbidden, email.status)
        assertEquals("channel_disabled", email.errCode())
    }

    @Test
    fun `refresh rotates and reuse detection revokes the family`() = withApp { client, _ ->
        val created = client.register("33600000006", "a-reasonable-pass-6")
        val refresh1 = created.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        val rotated = client.post("/v1/auth/refresh", """{"refreshToken":"$refresh1"}""")
        assertEquals(HttpStatusCode.OK, rotated.status)
        val refresh2 = rotated.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        assertNotEquals(refresh1, refresh2)
        // reuse the first refresh token -> family revoked
        val reuse = client.post("/v1/auth/refresh", """{"refreshToken":"$refresh1"}""")
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)
        assertEquals("refresh_reuse_detected", reuse.errCode())
        // the rotated generation is dead too
        val dead = client.post("/v1/auth/refresh", """{"refreshToken":"$refresh2"}""")
        assertEquals(HttpStatusCode.Unauthorized, dead.status)
    }

    @Test
    fun `logout revokes the refresh token`() = withApp { client, _ ->
        val created = client.register("33600000007", "a-reasonable-pass-7")
        val refresh = created.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        val out = client.post("/v1/auth/logout", """{"refreshToken":"$refresh"}""")
        assertEquals(HttpStatusCode.NoContent, out.status)
        val after = client.post("/v1/auth/refresh", """{"refreshToken":"$refresh"}""")
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    @Test
    fun `me endpoint requires a valid bearer token`() = withApp { client, _ ->
        val none = client.get("/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertEquals("unauthorized", none.errCode())
        val garbage = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer not.a.jwt") }
        assertEquals(HttpStatusCode.Unauthorized, garbage.status)
        // tokens signed by another secret also fail
        val other = JwtService("another-secret-32-bytes-long-minimum!!".toByteArray())
        val foreign = client.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer ${other.issueAccess("x", "RIDER")}") }
        assertEquals(HttpStatusCode.Unauthorized, foreign.status)
    }

    @Test
    fun `405 responses carry RFC 9110 Allow for the auth surface`() = withApp { client, _ ->
        val mePost = client.post("/v1/me")
        assertEquals(HttpStatusCode.MethodNotAllowed, mePost.status)
        assertEquals("GET", mePost.headers["Allow"])
        val regGet = client.get("/v1/auth/register")
        assertEquals(HttpStatusCode.MethodNotAllowed, regGet.status)
        assertEquals("POST", regGet.headers["Allow"])
    }

    @Test
    fun `malformed request bodies return documented 400 (ST101 and ST103 fix)`() = withApp { client, _ ->
        for (path in listOf("/v1/auth/register", "/v1/auth/login/password", "/v1/auth/otp/request", "/v1/auth/otp/verify", "/v1/auth/refresh", "/v1/auth/logout")) {
            val res = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody("\u0000")
            }
            assertEquals(HttpStatusCode.BadRequest, res.status, "path $path")
            assertEquals("invalid_body", res.errCode())
        }
    }

    @Test
    fun `test routes are still absent when test profile is off`() = withApp(testProfile = false) { client, _ ->
        val res = client.get("/test/otp/latest?target=33600000005")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}

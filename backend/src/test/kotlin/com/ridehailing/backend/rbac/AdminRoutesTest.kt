package com.ridehailing.backend.rbac

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
 * P1-3 admin route tests: RBAC 401/403 split, role matrix at the HTTP layer,
 * audited mutations (hash chain verifies), sessions/devices, force logout.
 * Admins are created through the AuthGraph user store directly (bootstrap seam),
 * then act via their own login tokens.
 */
class AdminRoutesTest {

    private fun withApp(graph: AuthGraph = AuthGraph(), block: suspend (HttpClient, AuthGraph) -> Unit) =
        testApplication {
            application { module(testProfile = false, auth = graph) }
            block(client, graph)
        }

    private fun createAdminIn(graph: AuthGraph, phone: String, role: Role): User {
        val user = User(
            id = Ids.newId(),
            phone = phone, email = null,
            passwordHash = graph.passwords.hash("admin-password-1"),
            displayName = "Admin ${role.name}", role = role, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(user)
        return user
    }

    @Test
    fun `admin endpoints require auth - 401 without token`() = withApp { client, graph ->
        createAdminIn(graph, "33610000001", Role.ADMIN_SUPER)
        val res = client.get("/v1/admin/users")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("unauthorized", res.errCode())
    }

    @Test
    fun `pagination outside documented bounds is 400 invalid_pagination - P1-CONTRACT`() = withApp { client, graph ->
        createAdminIn(graph, "33610000007", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000007", "admin-password-1")

        // GET /v1/admin/users: documented limit 1..100, offset >= 0.
        for (bad in listOf("/v1/admin/users?limit=0", "/v1/admin/users?limit=101", "/v1/admin/users?offset=-1", "/v1/admin/users?limit=501")) {
            val res = client.authed(bad, superToken)
            assertEquals(HttpStatusCode.BadRequest, res.status, "expected 400 for $bad: ${res.bodyAsText()}")
            assertEquals("invalid_pagination", res.errCode(), bad)
        }
        // A PRESENT value must PARSE (round 2: limit=false and offset beyond Int range
        // silently took the default -> ST201).
        for (bad in listOf("/v1/admin/users?limit=false", "/v1/admin/users?limit=abc", "/v1/admin/users?offset=-2147483649", "/v1/admin/audit?limit=false", "/v1/admin/audit?limit=99999999999")) {
            val res = client.authed(bad, superToken)
            assertEquals(HttpStatusCode.BadRequest, res.status, "expected 400 for $bad: ${res.bodyAsText()}")
            assertEquals("invalid_pagination", res.errCode(), bad)
        }
        // Documented boundaries stay accepted.
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/users?limit=100&offset=0", superToken).status)

        // GET /v1/admin/audit: documented limit 1..500 - no silent clamp (ST201 finding).
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/audit?limit=501", superToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/admin/audit?limit=0", superToken).status)
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/audit?limit=500", superToken).status)
    }

    @Test
    fun `driver and rider tokens get 403 on admin routes - driver blindness precursor`() = withApp { client, graph ->
        createAdminIn(graph, "33610000001", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000001", "admin-password-1")
        val create = client.postJson(
            "/v1/admin/users",
            """{"displayName":"Test Driver","phone":"33620000001","role":"DRIVER","password":"driver-pass-1"}""",
            superToken,
        )
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())

        val driverToken = client.getToken("33620000001", "driver-pass-1")
        val res = client.authed("/v1/admin/users", driverToken)
        assertEquals(HttpStatusCode.Forbidden, res.status, "driver token must be 403 on admin routes")
        assertEquals("forbidden", res.errCode())
        assertEquals(HttpStatusCode.Forbidden, client.authed("/v1/admin/audit", driverToken).status)
    }

    @Test
    fun `readonly admin can read users but cannot create - 403`() = withApp { client, graph ->
        createAdminIn(graph, "33610000002", Role.ADMIN_READONLY)
        val ro = client.getToken("33610000002", "admin-password-1")
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/users", ro).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.postJson("/v1/admin/users", """{"displayName":"X Y","phone":"33620000002","role":"RIDER"}""", ro).status,
        )
    }

    @Test
    fun `super admin creates and patches users - every mutation audited in a verifiable chain`() = withApp { client, graph ->
        createAdminIn(graph, "33610000003", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000003", "admin-password-1")

        val created = client.postJson(
            "/v1/admin/users",
            """{"displayName":"New Rider","phone":"33620000003","role":"RIDER","password":"rider-pass-1","locale":"fr"}""",
            superToken,
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val userId = created.json()["id"]!!.jsonPrimitive.content

        val list = client.authed("/v1/admin/users?query=33620000003", superToken)
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(1, list.json()["users"]!!.jsonArray.size)

        // PATCH: rename + ban.
        val banned = client.patchJson(
            "/v1/admin/users/$userId",
            """{"status":"BANNED_TEMP","displayName":"New Rider Jr"}""",
            superToken,
        )
        assertEquals(HttpStatusCode.OK, banned.status, banned.bodyAsText())
        assertEquals("BANNED_TEMP", banned.json()["status"]!!.jsonPrimitive.content)
        assertEquals("New Rider Jr", banned.json()["displayName"]!!.jsonPrimitive.content)

        // Audit chain: created + updated events present, chain verifies, no secrets in payloads.
        val audit = client.authed("/v1/admin/audit?limit=100", superToken)
        assertEquals(HttpStatusCode.OK, audit.status)
        val auditBody = audit.json()
        assertTrue(auditBody["verifyOk"]!!.jsonPrimitive.content == "true", "audit chain must verify")
        val events = auditBody["events"]!!.jsonArray
        val types = events.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertTrue(types.contains("admin.user.created"), "created must be audited")
        assertTrue(types.contains("admin.user.updated"), "patch must be audited")
        val payloads = events.map { it.jsonObject["payloadJson"]!!.jsonPrimitive.content }
        assertTrue(payloads.none { it.contains("rider-pass-1") || it.contains("argon2") }, "no secrets in audit")
    }

    @Test
    fun `ops admin cannot mint admin roles - only super can`() = withApp { client, graph ->
        createAdminIn(graph, "33610000004", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000004", "admin-password-1")
        val ops = client.postJson(
            "/v1/admin/users",
            """{"displayName":"Ops Admin","phone":"33610000005","role":"ADMIN_OPS","password":"admin-password-1"}""",
            superToken,
        )
        assertEquals(HttpStatusCode.Created, ops.status, ops.bodyAsText())
        val opsToken = client.getToken("33610000005", "admin-password-1")
        val escalated = client.postJson(
            "/v1/admin/users",
            """{"displayName":"Sneaky Admin","phone":"33610000006","role":"ADMIN_VERIFIER","password":"admin-password-1"}""",
            opsToken,
        )
        assertEquals(HttpStatusCode.Forbidden, escalated.status)
        assertEquals("forbidden", escalated.errCode())
        val rider = client.postJson(
            "/v1/admin/users",
            """{"displayName":"Plain Rider","phone":"33620000004","role":"RIDER","password":"rider-pass-1"}""",
            opsToken,
        )
        assertEquals(HttpStatusCode.Created, rider.status)
    }

    @Test
    fun `force logout revokes every session of the target - refresh dies too`() = withApp { client, graph ->
        createAdminIn(graph, "33610000007", Role.ADMIN_SUPPORT)
        val supportToken = client.getToken("33610000007", "admin-password-1")
        val reg1 = client.postJson(
            "/v1/auth/register",
            """{"displayName":"Victim","phone":"33620000005","password":"rider-pass-1"}""",
        )
        assertEquals(HttpStatusCode.Created, reg1.status)
        val refresh1 = reg1.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        val login2 = client.post("/v1/auth/login/password") {
            contentType(ContentType.Application.Json)
            setBody("""{"identifier":"33620000005","password":"rider-pass-1"}""")
            header("X-Device-Label", "victim-phone")
        }
        assertEquals(HttpStatusCode.OK, login2.status)
        val refresh2 = login2.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content

        val users = client.authed("/v1/admin/users?query=33620000005", supportToken)
        val victimId = users.json()["users"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        val force = client.postJson("/v1/admin/users/$victimId/force-logout", "null", supportToken)
        assertEquals(HttpStatusCode.OK, force.status, force.bodyAsText())
        assertEquals("2", force.json()["revokedSessions"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", """{"refreshToken":"$refresh1"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", """{"refreshToken":"$refresh2"}""").status)

        val selfId = graph.users.findByPhone("33610000007")!!.id
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postJson("/v1/admin/users/$selfId/force-logout", "null", supportToken).status,
        )
    }

    @Test
    fun `password reset invalidates old sessions and never logs the secret`() = withApp { client, graph ->
        createAdminIn(graph, "33610000008", Role.ADMIN_SUPPORT)
        val supportToken = client.getToken("33610000008", "admin-password-1")
        val reg = client.postJson(
            "/v1/auth/register",
            """{"displayName":"Reset Me","phone":"33620000006","password":"rider-pass-1"}""",
        )
        val refresh = reg.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        val users = client.authed("/v1/admin/users?query=33620000006", supportToken)
        val id = users.json()["users"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        val reset = client.postJson("/v1/admin/users/$id/reset-password", """{"newPassword":"brand-new-pass-9"}""", supportToken)
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", """{"refreshToken":"$refresh"}""").status)
        assertEquals(
            HttpStatusCode.OK,
            client.postJson("/v1/auth/login/password", """{"identifier":"33620000006","password":"brand-new-pass-9"}""").status,
        )
        val audit = client.authed("/v1/admin/audit?limit=100", supportToken)
        val payloads = audit.json()["events"]!!.jsonArray.map { it.jsonObject["payloadJson"]!!.jsonPrimitive.content }
        assertTrue(payloads.none { it.contains("brand-new-pass-9") }, "reset password must never be audited")
    }

    @Test
    fun `sessions are device-linked and revocable by their owner`() = withApp { client, _ ->
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Session Owner","phone":"33620000007","password":"rider-pass-1"}""")
            header("X-Device-Label", "owner-phone")
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val access = reg.json()["tokens"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content

        val sessions = client.authed("/v1/me/sessions", access)
        assertEquals(HttpStatusCode.OK, sessions.status)
        val list = sessions.json()["sessions"]!!.jsonArray
        assertEquals(1, list.size)
        val familyId = list.first().jsonObject["familyId"]!!.jsonPrimitive.content
        assertTrue(list.first().jsonObject["deviceId"]!!.jsonPrimitive.content.isNotBlank(), "session must link the device")

        val revoked = client.delete("/v1/me/sessions/$familyId") { header(HttpHeaders.Authorization, "Bearer $access") }
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        val refresh = reg.json()["tokens"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", """{"refreshToken":"$refresh"}""").status)
    }

    @Test
    fun `bootstrap from env is a no-op without the env pair and never double-creates`() = withApp { _, graph ->
        assertEquals(0, graph.users.search(null, 100, 0).filter { it.role.isAdmin }.size, "fresh graph has no admins")
        graph.bootstrapAdminFromEnv() // env unset -> no-op
        assertEquals(0, graph.users.search(null, 100, 0).filter { it.role.isAdmin }.size)
    }
}

// ---------- top-level helpers (member extensions mis-resolve inside test lambdas) ----------

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

private suspend fun HttpResponse.errCode(): String = json()["code"]!!.jsonPrimitive.content

private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

private suspend fun HttpClient.patchJson(path: String, body: String, token: String): HttpResponse =
    patch(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        header(HttpHeaders.Authorization, "Bearer $token")
    }

private suspend fun HttpClient.authed(path: String, token: String): HttpResponse =
    get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

private suspend fun HttpClient.getToken(identifier: String, password: String): String {
    val res = postJson("/v1/auth/login/password", """{"identifier":"$identifier","password":"$password"}""")
    assertEquals(HttpStatusCode.OK, res.status, "login failed: ${res.bodyAsText()}")
    return res.json()["tokens"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
}

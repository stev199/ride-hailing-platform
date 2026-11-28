package com.ridehailing.backend.wallet

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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-1 wallet routes: owner-scoped surfaces (withUser), admin credit RBAC
 * (MONEY_WRITE), verbatim error codes, pagination contract, and the wallet
 * config GET/PATCH (CONFIG_READ/WRITE, D75 full-replace).
 */
class WalletRoutesTest {

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

    @Test
    fun `balances, transfer errors verbatim, pagination contract`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33220000001", Role.RIDER)
        val riderToken = client.getToken("33220000001", "password-1")
        createUserIn(graph, "33220000002", Role.RIDER)
        createUserIn(graph, "33220000009", Role.ADMIN_FINANCE)
        val financeToken = client.getToken("33220000009", "password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/wallet").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/wallet/transfer", "{}").status)

        // Admin credit seeds the rider wallet (MONEY_WRITE: ADMIN_FINANCE holds it).
        val credit = client.postJson("/v1/admin/users/${rider.id}/wallet-credit", """{"amountMinor":500,"reason":"support top-up"}""", financeToken)
        assertEquals(HttpStatusCode.OK, credit.status, credit.bodyAsText())

        val balances = client.authed("/v1/wallet", riderToken)
        assertEquals(HttpStatusCode.OK, balances.status)
        val body = balances.json()
        assertEquals(500L, body["balances"]!!.jsonObject["POINTS"]!!.jsonPrimitive.content.toLong())
        assertEquals(0L, body["heldMinor"]!!.jsonPrimitive.content.toLong())

        // Transfer errors surface VERBATIM codes (D49 discipline).
        // Unknown phone -> 404 with the UNIFORM not_found body (app-wide existence-leak
        // prevention: every 404 in this service carries the same code/message shape).
        val unknown = client.postJson("/v1/wallet/transfer", """{"toPhone":"33999999999","amountMinor":100}""", riderToken)
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("not_found", unknown.json()["code"]!!.jsonPrimitive.content)
        val self = client.postJson("/v1/wallet/transfer", """{"toPhone":"33220000001","amountMinor":100}""", riderToken)
        assertEquals("self_transfer", self.json()["code"]!!.jsonPrimitive.content)
        val zero = client.postJson("/v1/wallet/transfer", """{"toPhone":"33220000002","amountMinor":0}""", riderToken)
        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals("invalid_amount", zero.json()["code"]!!.jsonPrimitive.content)

        // Admin credit with a blank reason -> 400 (the route guards the reason).
        val noReason = client.postJson("/v1/admin/users/${rider.id}/wallet-credit", """{"amountMinor":100,"reason":" "}""", financeToken)
        assertEquals(HttpStatusCode.BadRequest, noReason.status)
        assertEquals("invalid_reason", noReason.json()["code"]!!.jsonPrimitive.content)

        // Pagination contract: present-but-malformed params -> 400 invalid_pagination.
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/wallet/transactions?limit=0", riderToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/wallet/transactions?limit=nope", riderToken).status)
        assertEquals(HttpStatusCode.BadRequest, client.authed("/v1/wallet/transactions?offset=-1", riderToken).status)
        val page = client.authed("/v1/wallet/transactions?limit=1", riderToken)
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.json()["items"].toString().contains("wallet.admin_credit"), "history records the credit")
    }

    @Test
    fun `admin credit RBAC - MONEY_WRITE required, readonly refused`() = withApp { client, graph ->
        val target = createUserIn(graph, "33220000011", Role.RIDER)
        createUserIn(graph, "33220000012", Role.ADMIN_READONLY)
        val roToken = client.getToken("33220000012", "password-1")

        val forbidden = client.postJson("/v1/admin/users/${target.id}/wallet-credit", """{"amountMinor":100,"reason":"x"}""", roToken)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun `wallet config GET and PATCH - full-replace validation and version bump`() = withApp { client, graph ->
        createUserIn(graph, "33220000021", Role.ADMIN_OPS)
        val opsToken = client.getToken("33220000021", "password-1")

        val before = client.authed("/v1/admin/config/wallet", opsToken)
        assertEquals(HttpStatusCode.OK, before.status)
        val v0 = before.json()["transferFeeBps"]!!.jsonPrimitive.content

        val patch = client.patch("/v1/admin/config/wallet") {
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Authorization, "Bearer $opsToken")
            setBody("""{"transferFeeBps":150,"minTransferMinor":10,"dailyTransferLimitMinor":1000}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status, patch.bodyAsText())
        val patched = patch.json()
        assertTrue(patched["version"]!!.jsonPrimitive.content.toLong() > 0, "version bumped")
        assertEquals("150", patched["config"]!!.jsonObject["transferFeeBps"]!!.jsonPrimitive.content)

        // Invalid full-replace -> 400, live state untouched, no version burn.
        val bad = client.patch("/v1/admin/config/wallet") {
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Authorization, "Bearer $opsToken")
            setBody("""{"transferFeeBps":5000,"minTransferMinor":10,"dailyTransferLimitMinor":1000}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_wallet", bad.json()["code"]!!.jsonPrimitive.content)
        // Above the documented cap -> 400 (the value would be ECHOED in responses otherwise).
        val overCap = client.patch("/v1/admin/config/wallet") {
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Authorization, "Bearer $opsToken")
            setBody("""{"transferFeeBps":0,"minTransferMinor":1,"dailyTransferLimitMinor":1000000000000000001}""")
        }
        assertEquals(HttpStatusCode.BadRequest, overCap.status)

        val after = client.authed("/v1/admin/config/wallet", opsToken)
        assertEquals("150", after.json()["transferFeeBps"]!!.jsonPrimitive.content, "patched value stays live")
        assertTrue(v0.isNotEmpty())
    }
}

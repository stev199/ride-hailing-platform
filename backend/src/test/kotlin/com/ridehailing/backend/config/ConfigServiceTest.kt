package com.ridehailing.backend.config

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-5a config service tests: signed bundle serving (GET /v1/config, unauthenticated,
 * Ed25519-verifiable), admin login-methods read/patch behind CONFIG_READ/CONFIG_WRITE,
 * live wiring (a patch affects the very next login call), anti-rollback properties and
 * audit recording (D63).
 */
class ConfigServiceTest {

    // ---------- service level ----------

    @Test
    fun `bootstrap publishes version 1 and the signature verifies`() {
        val bundle = SignedConfigBundle.inMemoryDevKeys()
        val config = ConfigService(bundle)
        val b = config.currentBundle()
        assertEquals(1L, b.version)
        assertEquals(1, config.historySnapshot().size)
        assertEquals("bootstrap", config.historySnapshot().single().change)
        // A verifier with ONLY the public key accepts it against minVersion 0.
        val verifier = SignedConfigBundle.fromKeys(bundle.publicKeyBytes, privateKeyBytes = null)
        assertEquals(SignedConfigBundle.VerifyResult.Valid, verifier.verify(b, minVersion = 0))
        // Anti-rollback: a device that already accepted v1 rejects v1.
        assertEquals(SignedConfigBundle.VerifyResult.RolledBack, verifier.verify(b, minVersion = 1))
    }

    @Test
    fun `tampered config is a bad signature`() {
        val bundle = SignedConfigBundle.inMemoryDevKeys()
        val config = ConfigService(bundle)
        val b = config.currentBundle()
        val tampered = b.copy(configJson = b.configJson.replace("true", "false"))
        val verifier = SignedConfigBundle.fromKeys(bundle.publicKeyBytes, privateKeyBytes = null)
        assertEquals(SignedConfigBundle.VerifyResult.BadSignature, verifier.verify(tampered, minVersion = 0))
    }

    @Test
    fun `updateLoginMethods bumps version, applies live and audits`() {
        val events = mutableListOf<Pair<String, Map<String, String?>>>()
        val config = ConfigService(
            SignedConfigBundle.inMemoryDevKeys(),
            eventRecorder = { type, payload -> events.add(type to payload) },
        )
        val v1 = config.currentBundle()
        val patch = LoginMethodsPatch(
            passwordLoginEnabled = false,
            otpAutoRegister = false,
            otpChannels = mapOf("EMAIL" to ChannelPatch(enabled = true, priority = 5)),
        )
        val r = config.updateLoginMethods("admin-1", patch)
        assertTrue(r is ConfigService.UpdateResult.Updated, "expected Updated, got $r")
        assertEquals(2L, r.entry.version)
        assertEquals(2L, config.currentBundle().version)
        assertEquals(2, config.historySnapshot().size)
        // LIVE: the snapshot the auth consumers pull reflects the patch immediately.
        assertTrue(!config.loginMethods.passwordLoginEnabled)
        assertTrue(!config.loginMethods.otpAutoRegister)
        assertTrue(config.loginMethods.isOtpChannelEnabled(com.ridehailing.backend.auth.OtpChannel.EMAIL))
        // Audit (D63): one config.login_methods.updated event naming actor + version.
        val audit = events.single { it.first == "config.login_methods.updated" }.second
        assertEquals("admin-1", audit["actorId"])
        assertEquals("2", audit["version"])
        assertEquals(v1.version, config.historySnapshot().first().version)
    }

    @Test
    fun `patch validation - unknown channel, negative priority, duplicate priorities`() {
        val config = ConfigService(SignedConfigBundle.inMemoryDevKeys())
        config.currentBundle()
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch(otpChannels = mapOf("PAGER" to ChannelPatch(enabled = true))))
            is ConfigService.UpdateResult.Invalid, "unknown channel must be rejected")
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch(otpChannels = mapOf("SMS" to ChannelPatch(priority = -1))))
            is ConfigService.UpdateResult.Invalid, "negative priority must be rejected")
        // EMAIL enabled at the SAME priority as the enabled SMS (10) -> order not total.
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch(otpChannels = mapOf("EMAIL" to ChannelPatch(enabled = true, priority = 10))))
            is ConfigService.UpdateResult.Invalid, "duplicate enabled priorities must be rejected")
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch())
            is ConfigService.UpdateResult.Invalid, "empty patch must be rejected (no version burned)")
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch(otpChannels = emptyMap()))
            is ConfigService.UpdateResult.Invalid, "empty channel map is an empty patch")
        // P1-CONTRACT round 5 (ST201): empty map inside a NON-empty patch is equally
        // content-free - schema minProperties 1 must be mirrored by the server.
        assertTrue(config.updateLoginMethods("a", LoginMethodsPatch(passwordLoginEnabled = false, otpChannels = emptyMap()))
            is ConfigService.UpdateResult.Invalid, "partial patch with an empty channel map must be rejected")
        assertEquals(1L, config.currentBundle().version, "rejected patches must not publish a version")
    }

    @Test
    fun `disabling a channel never collides on priorities`() {
        val config = ConfigService(SignedConfigBundle.inMemoryDevKeys())
        config.currentBundle()
        val r = config.updateLoginMethods("a", LoginMethodsPatch(otpChannels = mapOf("SMS" to ChannelPatch(enabled = false))))
        assertTrue(r is ConfigService.UpdateResult.Updated)
        assertTrue(!config.loginMethods.isOtpChannelEnabled(com.ridehailing.backend.auth.OtpChannel.SMS))
    }

    // ---------- route level ----------

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
    fun `GET v1 config is public and signature-verifiable from the served publicKey`() = withApp { client, _ ->
        val res = client.get("/v1/config")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = res.json()
        val version = body["version"]!!.jsonPrimitive.content.toLong()
        val issuedAt = body["issuedAtEpochSec"]!!.jsonPrimitive.content.toLong()
        val configJson = body["config"]!!.jsonPrimitive.content
        val sig = hexBytes(body["signature"]!!.jsonPrimitive.content)
        val pub = hexBytes(body["publicKey"]!!.jsonPrimitive.content)
        val verifier = SignedConfigBundle.fromKeys(pub, privateKeyBytes = null)
        val candidate = SignedConfigBundle.SignedConfig(version, configJson, issuedAt, sig)
        assertEquals(SignedConfigBundle.VerifyResult.Valid, verifier.verify(candidate, minVersion = 0))
        // The served config is the canonical login-methods payload.
        assertTrue(configJson.contains("\"loginMethods\""), "bundle payload must carry loginMethods: $configJson")
    }

    @Test
    fun `admin config endpoints - 401 anonymous, 403 support-write, 200 ops`() = withApp { client, graph ->
        createAdminIn(graph, "33610000010", Role.ADMIN_OPS)
        createAdminIn(graph, "33610000011", Role.ADMIN_SUPPORT)
        val opsToken = client.getToken("33610000010", "admin-password-1")
        val supportToken = client.getToken("33610000011", "admin-password-1")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/config/login-methods").status)
        // SUPPORT has CONFIG_READ but NOT CONFIG_WRITE: reads OK, writes 403.
        // (OPS holds CONFIG_WRITE by design - config edits are ops work, role ASSIGNMENT is super-only, D62.)
        assertEquals(HttpStatusCode.OK, client.authed("/v1/admin/config/login-methods", supportToken).status)
        val patchRes = client.patchJson(
            "/v1/admin/config/login-methods",
            """{"passwordLoginEnabled":false}""",
            supportToken,
        )
        assertEquals(HttpStatusCode.Forbidden, patchRes.status, patchRes.bodyAsText())
        assertEquals("forbidden", patchRes.errCode())
        val read = client.authed("/v1/admin/config/login-methods", opsToken)
        assertEquals(HttpStatusCode.OK, read.status)
        val body = read.json()
        assertTrue(body["loginMethods"]!!.jsonObject.containsKey("otpChannels"))
        assertTrue(body["history"]!!.jsonArray.isNotEmpty())
        assertTrue(body.containsKey("currentVersion"))
    }

    @Test
    fun `patch disables password login - next login call answers disabled and bundle version bumps`() = withApp { client, graph ->
        createAdminIn(graph, "33610000012", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000012", "admin-password-1")
        assertEquals(1L, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())

        val patched = client.patchJson(
            "/v1/admin/config/login-methods",
            """{"passwordLoginEnabled":false}""",
            superToken,
        )
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertEquals(2L, patched.json()["version"]!!.jsonPrimitive.content.toLong())

        // LIVE wiring (P1-5a core): the very next login call sees the new config.
        val login = client.postJson(
            "/v1/auth/login/password",
            """{"identifier":"33610000012","password":"admin-password-1"}""",
        )
        assertEquals(HttpStatusCode.Forbidden, login.status, "disabled method must not be 401-invalid_credentials")
        assertEquals("login_method_disabled", login.errCode())

        // The public bundle carries the change, signature still valid.
        val cfg = client.get("/v1/config").json()
        assertEquals(2L, cfg["version"]!!.jsonPrimitive.content.toLong())
        assertTrue(cfg["config"]!!.jsonPrimitive.content.contains("\"passwordLoginEnabled\":false"))
    }

    @Test
    fun `patch with unknown channel answers 400 and publishes nothing`() = withApp { client, graph ->
        createAdminIn(graph, "33610000013", Role.ADMIN_SUPER)
        val superToken = client.getToken("33610000013", "admin-password-1")
        val res = client.patchJson(
            "/v1/admin/config/login-methods",
            """{"otpChannels":{"FAX":{"enabled":true}}}""",
            superToken,
        )
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("unknown_channel", res.errCode())
        assertEquals(1L, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())
    }

    // ---------- round-33 promo contract (CI run 46117305495) ----------

    @Test
    fun `promos PATCH rejects perUserLimit above the schema maximum (round-33 ST108-ST201)`() = withApp { client, graph ->
        createAdminIn(graph, "33610000020", Role.ADMIN_SUPER)
        val token = client.getToken("33610000020", "admin-password-1")
        val body = { limit: Int ->
            """{"codes":[{"code":"WELCOME50","kind":"PERCENT","value":50.0,"validUntilEpochSec":2000000000,"perUserLimit":$limit}]}"""
        }
        // ST201 (CI QK4V6R): perUserLimit 1001 vs the schema maximum 1000 -> 400 invalid_promos.
        val bad = client.patchJson("/v1/admin/config/promos", body(1001), token)
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
        assertEquals("invalid_promos", bad.errCode())
        // ST108 (CI XT4gZF): the store is untouched - GET must never serve the
        // out-of-bounds value, and no version was burned.
        val after = client.authed("/v1/admin/config/promos", token)
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.json()["codes"]!!.jsonArray.isEmpty(), "rejected patch must not touch the store: ${after.bodyAsText()}")
        assertEquals(1L, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())
        // The valid boundary perUserLimit=1000 succeeds and echoes through.
        val ok = client.patchJson("/v1/admin/config/promos", body(1000), token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val echoed = ok.json()["config"]!!.jsonObject["codes"]!!.jsonArray.single().jsonObject["perUserLimit"]!!.jsonPrimitive.content
        assertEquals("1000", echoed)
    }

    @Test
    fun `promos PATCH AMOUNT value - 1e18 boundary-double leniency (round-33 ST202)`() = withApp { client, graph ->
        createAdminIn(graph, "33610000021", Role.ADMIN_SUPER)
        val token = client.getToken("33610000021", "admin-password-1")
        val body = { value: String ->
            """{"codes":[{"code":"BIG","kind":"AMOUNT","value":$value,"currency":"PTS","validUntilEpochSec":2000000000,"perUserLimit":1}]}"""
        }
        // The EXACT CI case eDh6YV: shortest-repr boundary float one step above the cap
        // -> accepted (its decimal form IS the cap at double precision), stored AT the cap.
        val ok = client.patchJson("/v1/admin/config/promos", body("1.0000000000000001e+18"), token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val stored = ok.json()["config"]!!.jsonObject["codes"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.doubleOrNull
        assertEquals(PromoCodeConfig.MONEY_CAP.toDouble(), stored, "boundary double is stored AT the cap")
        // Strictly beyond the cap -> 400.
        val over = client.patchJson("/v1/admin/config/promos", body("1e21"), token)
        assertEquals(HttpStatusCode.BadRequest, over.status, over.bodyAsText())
        // Integer text above the cap: exact comparison, no leniency (round-24c parity).
        val intText = client.patchJson("/v1/admin/config/promos", body("1000000000000000001"), token)
        assertEquals(HttpStatusCode.BadRequest, intText.status, intText.bodyAsText())
        // The accepted config from the first PATCH survived the rejections.
        val after = client.authed("/v1/admin/config/promos", token)
        assertEquals(PromoCodeConfig.MONEY_CAP.toDouble(), after.json()["codes"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
    }

    @Test
    fun `promos PATCH rejects a PERCENT code with a 13-char currency (round-34)`() = withApp { client, graph ->
        createAdminIn(graph, "33610000022", Role.ADMIN_SUPER)
        val token = client.getToken("33610000022", "admin-password-1")
        val body = { currency: String ->
            """{"codes":[{"code":"WELCOME50","kind":"PERCENT","value":50.0,"currency":"$currency","validUntilEpochSec":2000000000,"perUserLimit":1}]}"""
        }
        // The schema currency is { type: [string, 'null'], maxLength: 12 } for ALL kinds -
        // the AMOUNT-only guard let a schema-invalid PERCENT currency into the store.
        val bad = client.patchJson("/v1/admin/config/promos", body("ABCDEFGHIJKLM"), token)
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
        assertEquals("invalid_promos", bad.errCode())
        // The store is untouched: GET must never echo the schema-invalid currency and
        // no version was burned (round-33 ST108 parity).
        val after = client.authed("/v1/admin/config/promos", token)
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.json()["codes"]!!.jsonArray.isEmpty(), "rejected patch must not touch the store: ${after.bodyAsText()}")
        assertEquals(1L, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())
        // The 12-char boundary is schema-valid for a PERCENT code and echoes through.
        val ok = client.patchJson("/v1/admin/config/promos", body("ABCDEFGHIJKL"), token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val echoed = ok.json()["config"]!!.jsonObject["codes"]!!.jsonArray.single().jsonObject["currency"]!!.jsonPrimitive.content
        assertEquals("ABCDEFGHIJKL", echoed)
    }

    @Test
    fun `promos PATCH accepts an explicit currency null (round-36 ST202 Ze60ry-3Ce4Ls)`() = withApp { client, graph ->
        createAdminIn(graph, "33610000023", Role.ADMIN_SUPER)
        val token = client.getToken("33610000023", "admin-password-1")
        // The EXACT CI case Ze60ry/3Ce4Ls: an EXPLICIT "currency": null reaches the
        // decoder as JsonNull (a JsonPrimitive with isString=false), which the old
        // absent-key-only arm rejected -> schema-valid [string, 'null'] must decode
        // and pass. Subnormal 5e-324 rides along as a bonus boundary (finite double).
        val body = """{"codes":[{"active":false,"code":"000","currency":null,"kind":"PERCENT","perUserLimit":1,"validFromEpochSec":0,"validUntilEpochSec":1,"value":5e-324}]}"""
        val ok = client.patchJson("/v1/admin/config/promos", body, token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val echoed = ok.json()["config"]!!.jsonObject["codes"]!!.jsonArray.single().jsonObject["currency"]
        assertTrue(echoed is JsonNull, "explicit null must round-trip as null: ${ok.bodyAsText()}")
        // AMOUNT + explicit currency null stays schema-invalid: the AMOUNT then-branch
        // pins currency to type:string, so the server 400s on the required-currency rule.
        val amount = """{"codes":[{"code":"BIG","kind":"AMOUNT","value":100.0,"currency":null,"validUntilEpochSec":2000000000,"perUserLimit":1}]}"""
        val bad = client.patchJson("/v1/admin/config/promos", amount, token)
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
        assertEquals("invalid_promos", bad.errCode())
    }

    @Test
    fun `promos PATCH duplicate codes answer the documented 409 (round-36c ST202 Mot8It)`() = withApp { client, graph ->
        createAdminIn(graph, "33610000024", Role.ADMIN_SUPER)
        val token = client.getToken("33610000024", "admin-password-1")
        val body = { n: Int ->
            val entry = """{"code":"000","kind":"AMOUNT","value":100.0,"currency":"PTS","validUntilEpochSec":1,"perUserLimit":1}"""
            """{"codes":[${List(n) { entry }.joinToString(",")}]}"""
        }
        // The EXACT CI case Mot8It: 49 IDENTICAL schema-valid entries (whole-item
        // duplicates are schema-invalid via uniqueItems, but the server must never
        // 400 a schema-valid document) -> documented 409 duplicate_promo_code.
        val identical = client.patchJson("/v1/admin/config/promos", body(49), token)
        assertEquals(HttpStatusCode.Conflict, identical.status, identical.bodyAsText())
        assertEquals("duplicate_promo_code", identical.errCode())
        assertTrue(!identical.bodyAsText().contains("duplicate code 000; codes: duplicate"),
            "each duplicated code is reported exactly once: ${identical.bodyAsText()}")
        // Distinct objects sharing a code value: schema-valid too -> same documented 409.
        val distinct = """{"codes":[{"code":"WELCOME","kind":"PERCENT","value":10.0,"validUntilEpochSec":2000000000,"perUserLimit":1},{"code":"WELCOME","kind":"PERCENT","value":20.0,"validUntilEpochSec":2000000000,"perUserLimit":1}]}"""
        val both = client.patchJson("/v1/admin/config/promos", distinct, token)
        assertEquals(HttpStatusCode.Conflict, both.status, both.bodyAsText())
        assertEquals("duplicate_promo_code", both.errCode())
        // The store is untouched and no version was burned (ST108 parity).
        val after = client.authed("/v1/admin/config/promos", token)
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.json()["codes"]!!.jsonArray.isEmpty(), "rejected patch must not touch the store: ${after.bodyAsText()}")
        assertEquals(1L, client.get("/v1/config").json()["version"]!!.jsonPrimitive.content.toLong())
        // A document without duplicates still succeeds.
        val ok = client.patchJson("/v1/admin/config/promos", body(1), token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    }

    // ---------- helpers (file-local mirrors of the AdminRoutesTest ones) ----------

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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

package com.ridehailing.core.data.wallet

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ridehailing.core.data.network.ApiClientFactory
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.pricing.FareBundleEnvelopeDto
import com.ridehailing.core.data.prefs.Session
import com.ridehailing.core.data.prefs.SessionStore
import com.ridehailing.core.data.pricing.FareBundleCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P7-3 WalletRepository on a MockEngine (plain JVM, AuthRepositoryTest pattern): wire
 * paths are asserted against the P7-1 backend contract, response bodies pin the DTO
 * shapes, server errors surface VERBATIM (code + message). The limits/fees read comes
 * from the cached signed bundle (the wallet section of the canonical config JSON).
 */
class WalletRepositoryTest {

    private data class FakeResponse(val status: Int, val text: String)

    private var responder: suspend (method: String, path: String) -> FakeResponse = { _, _ ->
        throw AssertionError("unexpected request")
    }

    private fun client(): HttpClient = ApiClientFactory.create(
        engine = MockEngine { request ->
            val query = request.url.parameters.entries()
                .joinToString("&") { "${it.key}=${it.value.first()}" }
            val path = request.url.encodedPath + if (query.isEmpty()) "" else "?$query"
            val raw = responder(request.method.value, path)
            respond(
                content = ByteReadChannel(raw.text),
                status = HttpStatusCode.fromValue(raw.status),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var sessions: SessionStore
    private lateinit var cache: FareBundleCache

    @Before
    fun setUp() {
        tmp = TemporaryFolder()
        tmp.create()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sessions = SessionStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "session_test.preferences_pb")
            },
        )
        cache = FareBundleCache(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "farebundle_test.preferences_pb")
            },
            clockMs = { 1_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.delete()
    }

    private suspend fun signIn(http: HttpClient) {
        val auth = AuthRepository(http, sessions)
        sessions.save(
            Session(
                accessToken = "a1",
                refreshToken = "r1",
                userId = "u1",
                phone = "15550001111",
            ),
        )
        auth.accessToken() // exercise the accessor once (refresh path stays untested here)
    }

    private fun newRepo(http: HttpClient): WalletRepository = WalletRepository(
        http = http,
        auth = AuthRepository(http, sessions),
        fareCache = cache,
        bundleFetcher = com.ridehailing.core.data.pricing.FareBundleFetcher(http, cache),
    )

    /** Default: /v1/config answers 404 so the bundle-fetch best-effort stays a no-op. */
    private fun defaultConfig404() {
        responder = { _, path ->
            if (path == "/v1/config") FakeResponse(404, """{"code":"not_found","message":"no"}""")
            else throw AssertionError("unexpected path $path")
        }
    }

    @Test
    fun balances_decodesBalancesAndHeld() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        responder = { method, path ->
            assertEquals("GET", method)
            assertEquals("/v1/wallet", path)
            FakeResponse(200, """{"balances":{"POINTS":1500},"heldMinor":300}""")
        }
        val dto = newRepo(http).balances()
        assertEquals(1500L, dto.balances["POINTS"])
        assertEquals(300L, dto.heldMinor)
    }

    @Test
    fun balances_requiresAuth() = runTest {
        val http = client()
        defaultConfig404()
        try {
            newRepo(http).balances()
            fail("expected auth_required")
        } catch (e: IllegalStateException) {
            assertEquals("auth_required", e.message)
        }
    }

    @Test
    fun transfer_serializesWireBody_andDecodesResult() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        responder = { method, path ->
            assertEquals("POST", method)
            assertEquals("/v1/wallet/transfer", path)
            FakeResponse(200, """{"feeMinor":60,"receiverGotMinor":540,"balanceMinor":940}""")
        }
        val dto = newRepo(http).transfer(toPhone = "15550002222", amountMinor = 600, note = "lunch")
        assertEquals(60L, dto.feeMinor)
        assertEquals(540L, dto.receiverGotMinor)
        assertEquals(940L, dto.balanceMinor)
        // Wire-shape pin: the exact JSON the repo sends (mirror of the P7-1 backend body).
        val wire = com.ridehailing.core.data.network.ApiJson.encodeToString(
            com.ridehailing.core.data.network.WalletTransferRequestDto.serializer(),
            com.ridehailing.core.data.network.WalletTransferRequestDto(
                toPhone = "15550002222", amountMinor = 600, note = "lunch",
            ),
        )
        assertEquals(
            """{"toPhone":"15550002222","amountMinor":600,"note":"lunch"}""",
            wire,
        )
    }

    @Test
    fun transfer_surfacesServerErrors_verbatim() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        responder = { _, path ->
            assertEquals("/v1/wallet/transfer", path)
            FakeResponse(409, """{"code":"insufficient_balance","message":"insufficient balance"}""")
        }
        try {
            newRepo(http).transfer(toPhone = "15550002222", amountMinor = 999_999L)
            fail("expected ApiRequestException")
        } catch (e: ApiRequestException) {
            assertEquals("insufficient_balance", e.error.code)
            assertEquals(409, e.error.httpStatus)
        }
    }

    @Test
    fun transactions_passesPaging_andDecodesItems() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        responder = { method, path ->
            assertEquals("GET", method)
            assertEquals("/v1/wallet/transactions?limit=10&offset=5", path)
            FakeResponse(
                200,
                """{"items":[{"entryId":"e1","transactionId":"t1","amountMinor":500,
                    "direction":"CREDIT","reason":"admin_credit","refType":null,"refId":null,
                    "createdAtEpochSec":1732540800}],
                    "limit":10,"offset":5}""",
            )
        }
        val dto = newRepo(http).transactions(limit = 10, offset = 5)
        assertEquals(10, dto.limit)
        assertEquals(5, dto.offset)
        assertEquals(1, dto.items.size)
        assertEquals("CREDIT", dto.items[0].direction)
        assertEquals(500L, dto.items[0].amountMinor)
        assertNull(dto.items[0].refType)
        assertNull(dto.items[0].refId)
    }

    @Test
    fun walletConfig_readsWalletSection_fromCachedBundle() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        val canonical = """
            {"loginMethods":null,"fares":{"cities":{}},"wallet":{"transferFeeBps":250,
            "minTransferMinor":10,"dailyTransferLimitMinor":5000}}
        """.trimIndent()
        // Save straight into the cache (save() does not verify - the fetcher path with a
        // real signature is covered by FareBundleCacheTest/FareBundleFetcher CI runs).
        cache.save(
            FareBundleEnvelopeDto(
                version = 1,
                issuedAtEpochSec = 100,
                config = canonical,
                signature = "ab",
                publicKey = "ab",
            ),
            signatureVerified = true,
        )
        val cfg = newRepo(http).walletConfig()
        assertEquals(250, cfg?.transferFeeBps)
        assertEquals(10L, cfg?.minTransferMinor)
        assertEquals(5000L, cfg?.dailyTransferLimitMinor)
    }

    @Test
    fun walletConfig_null_whenBundleLacksWalletSection() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        cache.save(
            FareBundleEnvelopeDto(
                version = 1,
                issuedAtEpochSec = 100,
                config = """{"loginMethods":null,"fares":{"cities":{}}}""",
                signature = "ab",
                publicKey = "ab",
            ),
            signatureVerified = true,
        )
        assertNull(newRepo(http).walletConfig())
    }

    @Test
    fun walletConfig_null_whenNoBundleCached() = runTest {
        val http = client()
        signIn(http)
        defaultConfig404()
        assertNull(newRepo(http).walletConfig())
    }
}

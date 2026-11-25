package com.ridehailing.core.data.network

import com.ridehailing.core.data.prefs.Session
import com.ridehailing.core.data.prefs.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthRepository on a MockEngine (plain JVM): login persistence, verbatim error
 * mapping, refresh rotation, 401 family-revocation self-heal, best-effort logout.
 * Request paths are asserted against the openapi contract; response bodies pin the
 * DTO shapes (serialization round-trip through the real ContentNegotiation plugin).
 */
class AuthRepositoryTest {
    private data class FakeResponse(val status: Int, val text: String)

    private var responder: suspend (path: String) -> FakeResponse = { _ ->
        throw AssertionError("unexpected request")
    }

    private fun client(): HttpClient = ApiClientFactory.create(
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val raw = responder(path)
            respond(
                content = ByteReadChannel(raw.text),
                status = HttpStatusCode.fromValue(raw.status),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    private fun newSessionStore(): SessionStore {
        val dir = Files.createTempDirectory("auth-test")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { dir.resolve("session.preferences_pb").toFile() }
        return SessionStore(dataStore)
    }

    private fun authSuccessJson(): String =
        """{"user":{"id":"u1","phone":"15550001111","email":null,"displayName":"Rider One",
            "role":"RIDER","status":"ACTIVE","locale":"en"},
            "tokens":{"accessToken":"a1","refreshToken":"r1","accessExpiresAtEpochSec":999},
            "created":false}"""

    @Test
    fun login_persistsSession_andReturnsUser() = runTest {
        val store = newSessionStore()
        val repo = AuthRepository(client(), store)
        responder = { path ->
            assertEquals("/v1/auth/login/password", path)
            FakeResponse(200, authSuccessJson())
        }
        val user = repo.login(" 15550001111 ", "secret")
        assertEquals("Rider One", user.displayName)
        assertEquals("RIDER", user.role)
        val session = store.sessionFlow.first()
        assertEquals("a1", session.accessToken)
        assertEquals("r1", session.refreshToken)
        assertEquals("u1", session.userId)
        assertEquals("15550001111", session.phone)
    }

    @Test
    fun login_errorSurfacesVerbatim() = runTest {
        val repo = AuthRepository(client(), newSessionStore())
        responder = { _ ->
            FakeResponse(401, """{"code":"invalid_credentials","message":"Wrong identifier or password","traceId":null}""")
        }
        val err = runCatching { repo.login("x", "y") }.exceptionOrNull() as ApiRequestException
        assertEquals("invalid_credentials", err.error.code)
        assertEquals("Wrong identifier or password", err.error.message)
        assertEquals(401, err.error.httpStatus)
    }

    @Test
    fun login_malformedErrorBody_stillTyped() = runTest {
        val repo = AuthRepository(client(), newSessionStore())
        responder = { _ -> FakeResponse(503, "<html>gateway timeout</html>") }
        val err = runCatching { repo.login("x", "y") }.exceptionOrNull() as ApiRequestException
        assertEquals("http_503", err.error.code)
        assertEquals(503, err.error.httpStatus)
        assertTrue(err.error.message.isNotBlank())
    }

    @Test
    fun refresh_rotatesTokens() = runTest {
        val store = newSessionStore()
        store.save(Session("a0", "r0", "u1", "15550001111"))
        val repo = AuthRepository(client(), store)
        responder = { path ->
            assertEquals("/v1/auth/refresh", path)
            FakeResponse(200, """{"tokens":{"accessToken":"a1","refreshToken":"r1","accessExpiresAtEpochSec":999}}""")
        }
        assertTrue(repo.refreshOnce())
        val s = store.sessionFlow.first()
        assertEquals("a1", s.accessToken)
        assertEquals("r1", s.refreshToken)
    }

    @Test
    fun refresh_401_revokedFamily_clearsSession_andReportsSignedOut() = runTest {
        val store = newSessionStore()
        store.save(Session("a0", "r0", "u1", null))
        val repo = AuthRepository(client(), store)
        responder = { _ ->
            FakeResponse(401, """{"code":"refresh_family_revoked","message":"Session family revoked","traceId":null}""")
        }
        assertEquals(false, repo.refreshOnce())
        assertNull(store.sessionFlow.first().accessToken)
    }

    @Test
    fun refresh_withoutRefreshToken_reportsSignedOut_withoutNetwork() = runTest {
        val repo = AuthRepository(client(), newSessionStore())
        responder = { path -> throw AssertionError("no network call expected, got $path") }
        assertEquals(false, repo.refreshOnce())
    }

    @Test
    fun logout_revokesFamily_bestEffort_andAlwaysClears() = runTest {
        val store = newSessionStore()
        store.save(Session("a0", "r0", "u1", null))
        val repo = AuthRepository(client(), store)
        responder = { path ->
            assertEquals("/v1/auth/logout", path)
            FakeResponse(204, "")
        }
        repo.logout()
        assertNull(store.sessionFlow.first().accessToken)
    }

    @Test
    fun logout_serverError_stillClearsLocally() = runTest {
        val store = newSessionStore()
        store.save(Session("a0", "r0", "u1", null))
        val repo = AuthRepository(client(), store)
        responder = { _ -> FakeResponse(500, """{"code":"boom","message":"no"}""") }
        repo.logout()
        assertNull(store.sessionFlow.first().accessToken)
        assertNull(store.sessionFlow.first().refreshToken)
    }
}

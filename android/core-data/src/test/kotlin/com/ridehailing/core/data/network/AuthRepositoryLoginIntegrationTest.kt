package com.ridehailing.core.data.network

import com.ridehailing.core.data.prefs.Session
import com.ridehailing.core.data.prefs.SessionStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-15e: the app-side network chain against a REAL HTTP socket (no mock engine).
 *
 * CI #191's e2e caught the base URL documented but never applied - every app request
 * used a RELATIVE path on a host-less client and died before reaching any backend.
 * Mock-engine tests resolve relative paths by construction and could never see that
 * bug; this test pins the wiring that matters: ApiClientFactory.create(baseUrl) +
 * a relative request path must produce `<base>/v1/auth/login/password` on the wire,
 * and a successful login must persist the session for sessionFlow readers.
 */
class AuthRepositoryLoginIntegrationTest {

    private fun tempSessionStore(): SessionStore {
        val file = File.createTempFile("session-test", ".preferences_pb")
        file.deleteOnExit()
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { file },
        )
        return SessionStore(dataStore)
    }

    /** Minimal one-shot HTTP server: answers the FIRST request with the login JSON. */
    private fun oneShotServer(expectedBody: String): Triple<ServerSocket, Thread, () -> String> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var requestLine = "unseen"
        val thread = Thread {
            val sock = server.accept()
            sock.use { s ->
                val reader = s.getInputStream().bufferedReader()
                requestLine = reader.readLine() ?: ""
                // Drain headers (and the request body) so the response can go out cleanly.
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(bodyChars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                }
                val bytes = expectedBody.encodeToByteArray()
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(head.encodeToByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
        thread.start()
        return Triple(server, thread) { requestLine }
    }

    @Test
    fun `login hits baseUrl + relative path and persists the session`() = runBlocking {
        val successBody = Json.encodeToString(
            AuthSuccessDto.serializer(),
            AuthSuccessDto(
                user = UserDto(
                    id = "u-e2e",
                    phone = "+37760000000",
                    displayName = "E2E",
                    role = "rider",
                    status = "active",
                    locale = "en",
                ),
                tokens = TokenPairDto(
                    accessToken = "acc-token",
                    refreshToken = "ref-token",
                    accessExpiresAtEpochSec = 4_102_444_800L,
                ),
                created = false,
            ),
        )
        val (server, thread, requestLine) = oneShotServer(successBody)
        try {
            // The trailing slash mirrors what NetworkModule strips - base + RELATIVE path
            // is exactly the wiring the emulator app uses (BuildConfig.API_BASE_URL).
            val client = ApiClientFactory.create(
                baseUrl = "http://127.0.0.1:${server.localPort}/",
            )
            val sessionStore = tempSessionStore()
            val auth = AuthRepository(client, sessionStore)

            val user = auth.login("+37760000000", "Password1A!")

            assertEquals("u-e2e", user.id)
            // THE PROOF: base URL + relative path merged on the wire.
            assertEquals("POST /v1/auth/login/password HTTP/1.1", requestLine())
            val session: Session = sessionStore.sessionFlow.first()
            assertEquals("acc-token", session.accessToken)
            assertEquals("ref-token", session.refreshToken)
            assertEquals("u-e2e", session.userId)
            thread.join(5_000)
        } finally {
            server.close()
        }
    }
}

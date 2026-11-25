package com.ridehailing.core.data.network

import com.ridehailing.core.data.prefs.Session
import com.ridehailing.core.data.prefs.SessionStore
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Auth repository (P2-4): password login against POST /v1/auth/login/password, single-
 * flight refresh (rotating refresh token - reuse revokes the family, so EVERY refresh
 * must go through the mutex), best-effort logout that always clears local state.
 * Tokens live in the SessionStore (P2-1c); the repository is the ONLY writer.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApiClient private val client: HttpClient,
    private val sessionStore: SessionStore,
) {
    private val refreshMutex = Mutex()

    val sessionFlow: Flow<Session> = sessionStore.sessionFlow

    suspend fun currentSession(): Session = sessionStore.sessionFlow.first()

    suspend fun accessToken(): String? = currentSession().accessToken?.takeIf { it.isNotEmpty() }

    /** Login (or register-when-unknown, backend `created` flag is part of AuthSuccess). */
    suspend fun login(identifier: String, password: String): UserDto {
        val success = client.postTyped<AuthSuccessDto>(
            "/v1/auth/login/password",
            PasswordLoginRequest(identifier = identifier.trim(), password = password),
        )
        sessionStore.save(
            Session(
                accessToken = success.tokens.accessToken,
                refreshToken = success.tokens.refreshToken,
                userId = success.user.id,
                phone = success.user.phone,
            ),
        )
        return success.user
    }

    /**
     * Rotate the token pair (single-flight). Returns false ONLY when the session is gone
     * (401 = family revoked by reuse detection or server-side logout); other errors throw.
     */
    suspend fun refreshOnce(): Boolean = refreshMutex.withLock {
        val session = currentSession()
        val refreshToken = session.refreshToken?.takeIf { it.isNotEmpty() } ?: return@withLock false
        try {
            val refreshed = client.postTyped<RefreshedDto>(
                "/v1/auth/refresh",
                RefreshRequest(refreshToken = refreshToken),
            )
            sessionStore.save(
                session.copy(
                    accessToken = refreshed.tokens.accessToken,
                    refreshToken = refreshed.tokens.refreshToken,
                ),
            )
            true
        } catch (e: ApiRequestException) {
            if (e.error.httpStatus == HttpStatusCode.Unauthorized.value) {
                // consumed refresh token -> family revoked (B-04/D35); sign the user out
                sessionStore.clear()
                false
            } else {
                throw e
            }
        }
    }

    /** Best-effort family revocation; local session is ALWAYS cleared afterwards. */
    suspend fun logout() {
        val session = currentSession()
        try {
            session.refreshToken?.takeIf { it.isNotEmpty() }?.let {
                client.postTyped<Unit>("/v1/auth/logout", LogoutRequest(refreshToken = it))
            }
        } catch (_: Exception) {
            // server unreachable / already revoked - local sign-out proceeds regardless
        }
        sessionStore.clear()
    }
}

package com.ridehailing.core.data.pricing

import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.getTyped
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the signed config bundle (GET /v1/config - PUBLIC, pre-login) and stores it
 * in [FareBundleCache] with verification + anti-rollback applied. Offline-first: NEVER
 * throws - every failure mode is a typed [FareBundleFetcher.FetchResult] the caller
 * (P4-4 booking UI) renders or silently ignores while offline.
 */
@Singleton
class FareBundleFetcher @Inject constructor(
    @ApiClient private val http: HttpClient,
    private val cache: FareBundleCache,
) {
    sealed class FetchResult {
        data class Refreshed(val version: Long, val signatureVerified: Boolean?) : FetchResult()
        /** The server sent an older-or-equal version - cache untouched (anti-rollback). */
        data class RolledBack(val version: Long, val lastAccepted: Long) : FetchResult()
        data class Unreachable(val reason: String) : FetchResult()
        data class BadBundle(val reason: String) : FetchResult()
    }

    suspend fun refresh(): FetchResult = try {
        val env = http.getTyped<FareBundleEnvelopeDto>("/v1/config")
        val verified = FareBundleVerifier.verify(env)
        // Verify BEFORE storing: a tampered bundle must not poison the cache. An
        // unsupported platform (null) stores and records the skip honestly.
        if (verified == false) {
            FetchResult.BadBundle("signature verification failed for version ${env.version}")
        } else {
            val lastAccepted = cache.lastAcceptedVersion()
            if (cache.save(env, verified)) {
                FetchResult.Refreshed(env.version, verified)
            } else {
                FetchResult.RolledBack(env.version, lastAccepted)
            }
        }
    } catch (e: java.net.ConnectException) {
        FetchResult.Unreachable(e.message ?: "connect failed")
    } catch (e: java.util.concurrent.TimeoutException) {
        FetchResult.Unreachable(e.message ?: "timeout")
    } catch (e: io.ktor.client.network.sockets.ConnectTimeoutException) {
        FetchResult.Unreachable(e.message ?: "connect timeout")
    } catch (e: com.ridehailing.core.data.network.ApiRequestException) {
        FetchResult.Unreachable("http ${e.error.httpStatus}: ${e.error.code}")
    } catch (e: kotlinx.serialization.SerializationException) {
        FetchResult.BadBundle("unparseable bundle: ${e.message}")
    } catch (e: Exception) {
        FetchResult.Unreachable(e.message ?: e.javaClass.simpleName)
    }
}

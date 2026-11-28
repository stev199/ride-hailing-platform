package com.ridehailing.core.data.wallet

import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.ApiJson
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.DeviceWalletConfig
import com.ridehailing.core.data.network.WalletBalancesDto
import com.ridehailing.core.data.network.WalletHistoryDto
import com.ridehailing.core.data.network.WalletTransferRequestDto
import com.ridehailing.core.data.network.WalletTransferResultDto
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.network.apiErrorFrom
import com.ridehailing.core.data.pricing.CanonicalConfigDto
import com.ridehailing.core.data.pricing.FareBundleCache
import com.ridehailing.core.data.pricing.FareBundleFetcher
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P7-3 wallet repository - the single wallet entry point for the rider UI.
 *
 * Online-only by design (v1): the ledger is the money truth and the backend owns every
 * rule (daily caps, fee math, hold accounting), so there is NO offline wallet cache -
 * the UI renders verbatim server errors (code + message) exactly like the auth surface.
 * Limits/fees come from the cached signed config bundle (P7-1 wallet section): the
 * fetcher refreshes best-effort on every [walletConfig] read and a stale/absent bundle
 * yields null (the UI shows an honest "limits unavailable" line instead of guesses).
 */
@Singleton
class WalletRepository @Inject constructor(
    @ApiClient private val http: HttpClient,
    private val auth: AuthRepository,
    private val fareCache: FareBundleCache,
    private val bundleFetcher: FareBundleFetcher,
) {

    /** Balances + held (GET /v1/wallet). Throws on auth/network/server errors. */
    suspend fun balances(): WalletBalancesDto {
        val token = auth.accessToken() ?: error("auth_required")
        val response = http.get("/v1/wallet") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) throw ApiRequestException(apiErrorFrom(response))
        return response.body<WalletBalancesDto>()
    }

    /** Ledger history, newest-first server-side (GET /v1/wallet/transactions). */
    suspend fun transactions(limit: Int = 20, offset: Int = 0): WalletHistoryDto {
        val token = auth.accessToken() ?: error("auth_required")
        val response = http.get("/v1/wallet/transactions?limit=$limit&offset=$offset") {
            header("Authorization", "Bearer $token")
        }
        if (!response.status.isSuccess()) throw ApiRequestException(apiErrorFrom(response))
        return response.body<WalletHistoryDto>()
    }

    /**
     * Transfer points by phone (POST /v1/wallet/transfer). Server outcome surfaces
     * verbatim: 409 insufficient_balance / daily_limit_exceeded, 404 unknown_user,
     * 400 invalid_body / invalid_amount / min_transfer - the UI renders code+message.
     */
    suspend fun transfer(toPhone: String, amountMinor: Long, note: String? = null): WalletTransferResultDto {
        val token = auth.accessToken() ?: error("auth_required")
        val response = http.post("/v1/wallet/transfer") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(WalletTransferRequestDto(toPhone = toPhone.trim(), amountMinor = amountMinor, note = note))
        }
        if (!response.status.isSuccess()) throw ApiRequestException(apiErrorFrom(response))
        return response.body<WalletTransferResultDto>()
    }

    /**
     * Rider-visible wallet limits/fees from the signed config bundle (P7-1). Best-effort
     * refresh keeps it fresh while online; a cached-older bundle without the wallet
     * section (or no bundle at all, e.g. first launch offline) yields null.
     */
    suspend fun walletConfig(): DeviceWalletConfig? {
        runCatching { bundleFetcher.refresh() } // never throws - offline stays offline
        val bundle = fareCache.bundle() ?: return null
        return try {
            ApiJson.decodeFromString(CanonicalConfigDto.serializer(), bundle.configJson).wallet
        } catch (_: Exception) {
            null
        }
    }
}

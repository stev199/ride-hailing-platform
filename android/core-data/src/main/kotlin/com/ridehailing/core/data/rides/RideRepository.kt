package com.ridehailing.core.data.rides

import androidx.room.withTransaction
import com.ridehailing.core.data.db.AppDatabase
import com.ridehailing.core.data.db.RideEntity
import com.ridehailing.core.data.db.RideStatus
import com.ridehailing.core.data.db.statusFromServer
import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.ApiJson
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.FareEstimateRequestDto
import com.ridehailing.core.data.network.FareEstimateResponseDto
import com.ridehailing.core.data.network.RideCreateRequestDto
import com.ridehailing.core.data.network.RideDto
import com.ridehailing.core.data.network.RideListDto
import com.ridehailing.core.data.outbox.OutboxRepository
import com.ridehailing.core.data.outbox.OutboxResult
import com.ridehailing.core.data.outbox.OutboxTransport
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.pricing.CachedRoute
import com.ridehailing.core.data.pricing.FareBundleCache
import com.ridehailing.core.data.pricing.FareBundleFetcher
import com.ridehailing.core.data.pricing.FareEstimator
import com.ridehailing.core.data.pricing.FareMath
import com.ridehailing.core.data.pricing.OfflineEstimateOutcome
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outbox types owned by the rides domain. */
object RideOutbox {
    const val TYPE_RIDE_CREATE = "ride_create"
    const val TYPE_RIDE_CANCEL = "ride_cancel"
}

/**
 * P4-4 outbox transport for the rides domain: implements the P2-1c seam against the
 * backend rides API (P4-2 contract). Idempotency note (OutboxTransport KDoc): POST
 * /v1/rides mints a NEW server ride per call - v1 accepts that a drained create row
 * is never retried after Accept/Reject (the drain marks it terminal either way);
 * cancel retries are absorbed server-side by the REQUESTED-only state machine (409).
 *
 * On [OutboxResult.Accepted] the transport backfills the LOCAL row (serverId + quote
 * fare/currency, or CANCELLED status) - the outbox itself never touches domain truth.
 */
@Singleton
class RidesOutboxTransport @Inject constructor(
    @ApiClient private val http: HttpClient,
    private val auth: AuthRepository,
    private val db: AppDatabase,
    private val clock: Clock,
) : OutboxTransport {

    private suspend fun bearer(): String? = auth.accessToken()?.let { "Bearer $it" }

    private suspend fun rideIdFor(entityId: String): String? =
        db.rideDao().byId(entityId)?.serverId

    private suspend fun backfillCreate(entityId: String, ride: RideDto) {
        db.withTransaction {
            val local = db.rideDao().byId(entityId) ?: return@withTransaction
            db.rideDao().upsert(
                local.copy(
                    serverId = ride.id,
                    status = statusFromServer(ride.status),
                    // P7-6: the CHARGE (quote minus promo discount) is the money truth.
                    fareMinor = ride.chargeAmountMinor.takeIf { it > 0 } ?: ride.quote.amountMinor,
                    currency = ride.quote.currency,
                    updatedAtMs = clock.millis(),
                ),
            )
        }
    }

    override suspend fun send(type: String, entityId: String, payloadJson: String): OutboxResult {
        val token = bearer() ?: return OutboxResult.Rejected("auth_required: not signed in")
        return when (type) {
            RideOutbox.TYPE_RIDE_CREATE -> {
                val request = try {
                    ApiJson.decodeFromString(RideCreateRequestDto.serializer(), payloadJson)
                } catch (_: Exception) {
                    return OutboxResult.Rejected("bad_payload: unreadable ride create payload")
                }
                val response = http.post("/v1/rides") {
                    header("Authorization", token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (response.status.isSuccess()) {
                    val ride = response.body<RideDto>()
                    backfillCreate(entityId, ride)
                    OutboxResult.Accepted(ride.id)
                } else {
                    OutboxResult.Rejected(apiErrorText(response))
                }
            }
            RideOutbox.TYPE_RIDE_CANCEL -> {
                val serverId = rideIdFor(entityId)
                    ?: return OutboxResult.Rejected("no_server_id: ride never reached the backend")
                val response = http.post("/v1/rides/$serverId/cancel") {
                    header("Authorization", token)
                    contentType(ContentType.Application.Json)
                }
                if (response.status.isSuccess()) {
                    val ride = response.body<RideDto>()
                    db.rideDao().updateStatus(entityId, statusFromServer(ride.status), clock.millis())
                    OutboxResult.Accepted(ride.id)
                } else {
                    OutboxResult.Rejected(apiErrorText(response))
                }
            }
            else -> OutboxResult.Rejected("unknown_type: $type")
        }
    }

    private suspend fun apiErrorText(response: HttpResponse): String =
        try {
            val err = response.body<com.ridehailing.core.data.network.ApiErrorDto>()
            "${err.code}: ${err.message}"
        } catch (_: Exception) {
            "http_${response.status.value}"
        }
}

/** Draft the rider UI assembles before booking. */
data class RideDraft(
    val pickupLabel: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLabel: String,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val city: String,
    val rideType: String,
    val paymentMethod: String = "CASH",
    val note: String? = null,
    /** P7-6: optional promo code (server validates; the charge is discounted). */
    val promoCode: String? = null,
)

sealed class EstimateOutcome {
    /** Live server estimate (OSRM route when up). */
    data class Online(val response: FareEstimateResponseDto) : EstimateOutcome()
    /** Offline estimate from the cached signed bundle (+ cached route or straight-line). */
    data class Offline(val result: com.ridehailing.core.data.pricing.OfflineEstimateResult) : EstimateOutcome()
    /** No config cached and the server refused/absent - the verbatim error surfaces. */
    data class Unavailable(val error: String) : EstimateOutcome()
}

/**
 * P4-4 rides repository - the single booking entry point for the UI.
 *
 * Offline-first write path (spec: local truth first): book() writes the local
 * REQUESTED ride, enqueues the outbox row, then drains immediately - online gives a
 * server ack (serverId + signed quote backfilled), offline leaves the row PENDING for
 * the next drain (app start / connectivity regained, Phase 5 WorkManager).
 *
 * Estimates prefer the LIVE server math (OSRM + surge-when-known) and cache the route;
 * any failure falls back to the P4-3 on-device estimator (cached route -> calibrated
 * straight-line). The fare bundle refreshes opportunistically on every estimate.
 */
@Singleton
class RideRepository @Inject constructor(
    private val dao: com.ridehailing.core.data.db.RideDao,
    private val outbox: OutboxRepository,
    private val auth: AuthRepository,
    @ApiClient private val http: HttpClient,
    private val fareCache: FareBundleCache,
    private val bundleFetcher: FareBundleFetcher,
    private val estimator: FareEstimator,
    private val ridesTransport: RidesOutboxTransport,
    private val clock: Clock,
) {
    fun history(): kotlinx.coroutines.flow.Flow<List<RideEntity>> = dao.history()

    fun observe(rideId: String): kotlinx.coroutines.flow.Flow<RideEntity?> = dao.observeById(rideId)

    /** BOOK: local write -> outbox enqueue -> immediate drain. Returns the local ride id. */
    suspend fun book(draft: RideDraft): String {
        val rideId = UUID.randomUUID().toString()
        val nowMs = clock.millis()
        dao.upsert(
            RideEntity(
                rideId = rideId,
                serverId = null,
                riderId = "",
                driverId = null,
                status = RideStatus.REQUESTED,
                pickupLabel = draft.pickupLabel,
                pickupLat = draft.pickupLat,
                pickupLng = draft.pickupLng,
                dropoffLabel = draft.dropoffLabel,
                dropoffLat = draft.dropoffLat,
                dropoffLng = draft.dropoffLng,
                fareMinor = null,
                currency = null,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            ),
        )
        val payload = ApiJson.encodeToString(
            RideCreateRequestDto.serializer(),
            RideCreateRequestDto(
                pickupLat = draft.pickupLat,
                pickupLon = draft.pickupLng,
                dropoffLat = draft.dropoffLat,
                dropoffLon = draft.dropoffLng,
                city = draft.city,
                rideType = draft.rideType,
                paymentMethod = draft.paymentMethod,
                note = draft.note,
                pickupName = draft.pickupLabel,
                dropoffName = draft.dropoffLabel,
                promoCode = draft.promoCode,
            ),
        )
        outbox.enqueue(RideOutbox.TYPE_RIDE_CREATE, rideId, payload)
        outbox.drain(ridesTransport)
        return rideId
    }

    /** CANCEL: online-only in v1 (server state machine owns the fee rules). */
    suspend fun cancel(rideId: String): Result<Unit> = runCatching {
        val local = dao.byId(rideId) ?: error("ride_not_found")
        val serverId = local.serverId ?: error("not_synced")
        val token = auth.accessToken() ?: error("auth_required")
        val response = http.post("/v1/rides/$serverId/cancel") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw ApiRequestException(
                com.ridehailing.core.data.network.apiErrorFrom(response),
            )
        }
        val ride = response.body<RideDto>()
        dao.upsert(
            local.copy(status = statusFromServer(ride.status), updatedAtMs = clock.millis()),
        )
        Unit
    }

    /**
     * ESTIMATE: server-first, offline fallback. Also caches the live route for the
     * offline estimator and refreshes the fare bundle (best-effort, never throws).
     */
    suspend fun estimate(
        pickupLat: Double,
        pickupLon: Double,
        dropoffLat: Double,
        dropoffLon: Double,
        city: String,
        rideType: String? = null,
    ): EstimateOutcome {
        bundleFetcher.refresh()
        val token = auth.accessToken()
        if (token != null) {
            try {
                val response = http.post("/v1/rides/estimate") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        FareEstimateRequestDto(pickupLat, pickupLon, dropoffLat, dropoffLon, city, rideType),
                    )
                }
                if (response.status.isSuccess()) {
                    val est = response.body<FareEstimateResponseDto>()
                    fareCache.saveRoute(
                        CachedRoute(
                            pickupLat = pickupLat, pickupLon = pickupLon,
                            dropoffLat = dropoffLat, dropoffLon = dropoffLon,
                            distanceMeters = est.distanceMeters,
                            durationSeconds = est.durationSeconds,
                            cachedAtEpochSec = clock.instant().epochSecond,
                        ),
                    )
                    return EstimateOutcome.Online(est)
                }
            } catch (_: Exception) {
                // fall through to offline
            }
        }
        val cachedRoute = fareCache.routeFor(pickupLat, pickupLon, dropoffLat, dropoffLon)
        val offline = estimator.estimate(
            pickupLat = pickupLat, pickupLon = pickupLon,
            dropoffLat = dropoffLat, dropoffLon = dropoffLon,
            city = city, rideType = rideType,
            cachedRoute = cachedRoute?.let {
                FareMath.RouteInput(it.distanceMeters, it.durationSeconds)
            },
        )
        return when (offline) {
            is OfflineEstimateOutcome.Ok -> EstimateOutcome.Offline(offline.result)
            is OfflineEstimateOutcome.NoConfig ->
                EstimateOutcome.Unavailable(offline.reason)
            is OfflineEstimateOutcome.UnknownCity -> EstimateOutcome.Unavailable("unknown_city: $city")
            is OfflineEstimateOutcome.UnknownRideType ->
                EstimateOutcome.Unavailable("unknown_ride_type: ${offline.rideType}")
        }
    }

    /** Full signed quote for the trip screen (online; null when unreachable/not synced). */
    suspend fun remoteRide(rideId: String): RideDto? {
        val local = dao.byId(rideId) ?: return null
        val serverId = local.serverId ?: return null
        val token = auth.accessToken() ?: return null
        return try {
            val response = http.get("/v1/rides/$serverId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status == HttpStatusCode.OK) response.body<RideDto>() else null
        } catch (_: Exception) {
            null
        }
    }

    /** Server history -> local store (keeps local ids, inserts unknown server rides). */
    suspend fun refreshHistory(limit: Int = 20) {
        val token = auth.accessToken() ?: return
        try {
            val response = http.get("/v1/rides?limit=$limit&offset=0") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return
            val list = response.body<RideListDto>()
            val now = clock.millis()
            for (ride in list.rides) {
                val local = dao.byServerId(ride.id)
                val rideId = local?.rideId ?: ride.id
                dao.upsert(
                    RideEntity(
                        rideId = rideId,
                        serverId = ride.id,
                        riderId = "",
                        driverId = null,
                        status = statusFromServer(ride.status),
                        pickupLabel = ride.pickupName ?: "lat %.5f".format(ride.pickupLat),
                        pickupLat = ride.pickupLat,
                        pickupLng = ride.pickupLon,
                        dropoffLabel = ride.dropoffName ?: "lat %.5f".format(ride.dropoffLat),
                        dropoffLat = ride.dropoffLat,
                        dropoffLng = ride.dropoffLon,
                        fareMinor = ride.quote.amountMinor,
                        currency = ride.quote.currency,
                        createdAtMs = ride.createdAtEpochSec * 1000,
                        updatedAtMs = maxOf(ride.updatedAtEpochSec * 1000, if (local == null) now else 0),
                    ),
                )
            }
        } catch (_: Exception) {
            // offline - local history stays as-is
        }
    }
}

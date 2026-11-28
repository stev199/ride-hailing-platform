package com.ridehailing.backend.rides

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 6 admin trips routes (spec "Trips: live and historical"):
 * - GET /v1/admin/rides (TRIPS_READ) - ALL rides newest first with optional
 *   status/city filters and offset pagination. PII note: this is the admin
 *   surface - unlike the driver DTOs (no rider PII), admin sees riderId/driverId.
 * - GET /v1/admin/rides/{id} (TRIPS_READ) - the full ride + the ride.* timeline
 *   from the hash-chained event store (ride.requested/accepted/arrived/
 *   in_progress/completed/cancelled/expired, payloads carry rideId).
 * - `origin` is the spec's admin-only field ("online / offline-mesh" + mesh path);
 *   mesh-originated rides land with Phase 10 - v1 answers the constant "online",
 *   recorded honestly here and in the OpenAPI description.
 * The ride STATE MACHINE is never changed here (no admin cancel/edit yet - those
 * need the money/ledger integration and land with the Money phase work).
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/**
 * Pagination contract copied from AdminRoutes (D49 generator-precision): a PRESENT
 * limit/offset must parse as an integer; out-of-range = 400 invalid_pagination.
 */
private suspend fun ApplicationCall.paginationOr400(defaultLimit: Int, maxLimit: Int): Pair<Int, Int>? {
    suspend fun bad(detail: String): Nothing? {
        respond(HttpStatusCode.BadRequest, err("invalid_pagination", detail))
        return null
    }
    val rawLimit = request.queryParameters["limit"]
    val rawOffset = request.queryParameters["offset"]
    val limit = rawLimit?.toIntOrNull() ?: if (rawLimit != null) return bad("limit must be an integer in 1..$maxLimit") else defaultLimit
    val offset = rawOffset?.toIntOrNull() ?: if (rawOffset != null) return bad("offset must be a non-negative integer") else 0
    if (limit < 1 || limit > maxLimit || offset < 0) return bad("limit must be 1..$maxLimit, offset must be >= 0")
    return limit to offset
}

@Serializable
data class AdminRideListItemDto(
    val id: String,
    val status: String,
    val city: String,
    val rideType: String,
    val riderId: String,
    val driverId: String?,
    /** Spec's admin-only origin field; "offline-mesh" lands with Phase 10 (mesh rides). */
    val origin: String = "online",
    val paymentMethod: String,
    val quoteAmountMinor: Long,
    val quoteCurrency: String,
    val pickupName: String?,
    val dropoffName: String?,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
)

@Serializable
data class AdminTimelineEventDto(
    val seq: Long,
    val type: String,
    val occurredAt: String,
    val payloadJson: String,
)

@Serializable
data class AdminRideDetailDto(
    val ride: AdminRideListItemDto,
    /** ride.* events for this ride, chain order (oldest first). */
    val timeline: List<AdminTimelineEventDto>,
)

@Serializable
data class AdminRideListDto(
    val rides: List<AdminRideListItemDto>,
    val limit: Int,
    val offset: Int,
)

private fun Ride.toAdminListItemDto() = AdminRideListItemDto(
    id = id,
    status = status.name,
    city = city,
    rideType = rideType,
    riderId = riderId,
    driverId = driverId,
    origin = "online",
    paymentMethod = paymentMethod,
    quoteAmountMinor = quoteAmountMinor,
    quoteCurrency = quoteCurrency,
    pickupName = pickupName,
    dropoffName = dropoffName,
    cancelFeeMinor = cancelFeeMinor,
    createdAtEpochSec = createdAtEpochSec,
    updatedAtEpochSec = updatedAtEpochSec,
)

fun Route.adminTripRoutes(
    service: AuthService,
    store: RideStore,
    eventChain: EventChain,
    rideService: RideService,
    wallet: com.ridehailing.backend.wallet.WalletService,
) {
    route("/v1/admin/rides") {
        get {
            call.withPermission(service, Perm.TRIPS_READ) { _ ->
                val (limit, offset) = call.paginationOr400(defaultLimit = 50, maxLimit = 500)
                    ?: return@withPermission
                val status = call.request.queryParameters["status"]?.let { raw ->
                    RideStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: run {
                        call.respond(HttpStatusCode.BadRequest, err("invalid_status", "Unknown ride status: $raw"))
                        return@withPermission
                    }
                }
                val city = call.request.queryParameters["city"]?.trim()?.takeIf { it.isNotEmpty() }
                val rides = store.listAll(status, city, limit, offset)
                call.respond(HttpStatusCode.OK, AdminRideListDto(rides = rides.map { it.toAdminListItemDto() }, limit = limit, offset = offset))
            }
        }
        get("/{id}") {
            call.withPermission(service, Perm.TRIPS_READ) { _ ->
                val ride = store.byId(call.parameters["id"] ?: "")
                if (ride == null) {
                    // 404 for unknown AND not-found-by-design: no existence leak beyond admin scope.
                    call.respond(HttpStatusCode.NotFound, err("ride_not_found", "No such ride"))
                    return@withPermission
                }
                val timeline = eventChain.snapshot()
                    .filter { it.type.startsWith("ride.") && rideIdOf(it.payloadJson) == ride.id }
                    .map { AdminTimelineEventDto(seq = it.seq, type = it.type, occurredAt = it.occurredAt, payloadJson = it.payloadJson) }
                call.respond(HttpStatusCode.OK, AdminRideDetailDto(ride = ride.toAdminListItemDto(), timeline = timeline))
            }
        }
        // P7-5a: platform cancel (TRIPS_WRITE) - any non-terminal ride, fee-free,
        // pending WALLET hold released by the service (money returns to the rider).
        post("/{id}/cancel") {
            call.withPermission(service, Perm.TRIPS_WRITE) { user ->
                when (val res = rideService.adminCancel(user.id, call.parameters["id"] ?: "")) {
                    is RideService.CancelResult.Cancelled -> call.respond(
                        HttpStatusCode.OK,
                        AdminRideActionDto(rideId = res.ride.id, status = res.ride.status.name),
                    )
                    is RideService.CancelResult.Error -> call.respond(
                        HttpStatusCode.fromValue(res.status),
                        err(res.code, res.code.replace('_', ' ')),
                    )
                }
            }
        }
        // P7-5a: refund a COMPLETED WALLET ride (MONEY_WRITE) - exact capture
        // reversal, one atomic transaction; verbatim codes surfaced.
        post("/{id}/refund") {
            call.withPermission(service, Perm.MONEY_WRITE) { _ ->
                val ride = store.byId(call.parameters["id"] ?: "")
                if (ride == null) {
                    call.respond(HttpStatusCode.NotFound, err("ride_not_found", "No such ride"))
                    return@withPermission
                }
                if (ride.status != RideStatus.COMPLETED || ride.paymentMethod != "WALLET") {
                    call.respond(HttpStatusCode.Conflict, err("refund_not_allowed", "refund not allowed"))
                    return@withPermission
                }
                when (val res = wallet.refundCompletedRide(ride.riderId, ride.driverId ?: "", ride.id, ride.quoteAmountMinor)) {
                    is com.ridehailing.backend.wallet.WalletService.RefundResult.Done -> {
                        eventChain.append(
                            "money.refunded",
                            mapOf(
                                "rideId" to ride.id,
                                "riderGotMinor" to res.riderGotMinor.toString(),
                                "driverDebitedMinor" to res.driverDebitedMinor.toString(),
                                "commissionReversedMinor" to res.commissionReversedMinor.toString(),
                            ),
                        )
                        call.respond(
                            HttpStatusCode.OK,
                            AdminRefundDto(
                                rideId = ride.id,
                                riderGotMinor = res.riderGotMinor,
                                driverDebitedMinor = res.driverDebitedMinor,
                                commissionReversedMinor = res.commissionReversedMinor,
                            ),
                        )
                    }
                    is com.ridehailing.backend.wallet.WalletService.RefundResult.Invalid -> {
                        val status = when (res.code) {
                            "capture_not_found", "driver_wallet_missing", "insufficient_driver_balance" -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(status, err(res.code, res.code.replace('_', ' ')))
                    }
                }
            }
        }
    }
}

@Serializable
data class AdminRideActionDto(val rideId: String, val status: String)

@Serializable
data class AdminRefundDto(
    val rideId: String,
    val riderGotMinor: Long,
    val driverDebitedMinor: Long,
    val commissionReversedMinor: Long,
)

/** Extract payload.rideId from a chain event's canonical JSON (null when absent). */
private fun rideIdOf(payloadJson: String): String? = try {
    Json.parseToJsonElement(payloadJson).jsonObject["rideId"]?.jsonPrimitive?.content
} catch (_: IllegalArgumentException) {
    null
} catch (_: kotlinx.serialization.SerializationException) {
    null
}

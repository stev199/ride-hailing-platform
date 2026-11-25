package com.ridehailing.backend.rides

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.withUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * P4-2 ride routes (rider booking happy path):
 * - POST /v1/rides - RIDER role only (403 booking_role_required otherwise): creates the
 *   trip in REQUESTED with the server-SIGNED final quote (spec §pricing).
 * - GET /v1/rides - the rider's own rides, newest first.
 * - GET /v1/rides/{id} - rider-owned (404 for unknown AND not-owned: no existence leak).
 * - POST /v1/rides/{id}/cancel - rider-owned, REQUESTED only (409 cancel_not_allowed
 *   otherwise); applies the configured cancel fee to the ledger.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class RideCreateRequest(
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val city: String,
    val rideType: String,
    val paymentMethod: String = "CASH",
    val note: String? = null,
    val pickupName: String? = null,
    val dropoffName: String? = null,
)

@Serializable
data class QuoteDto(
    val amountMinor: Long,
    val currency: String,
    val mode: String,
    val meteredCapMinor: Long,
    val issuedAtEpochSec: Long,
    val validUntilEpochSec: Long,
    val payload: String,
    val signature: String,
    /** Ed25519 public key (X.509 SPKI DER hex) - the device verifies the quote against it. */
    val publicKeyHex: String,
)

@Serializable
data class RideDto(
    val id: String,
    val status: String,
    val city: String,
    val rideType: String,
    val paymentMethod: String,
    val note: String?,
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val pickupName: String?,
    val dropoffName: String?,
    val quote: QuoteDto,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
)

@Serializable
data class RideListDto(
    val rides: List<RideDto>,
    val limit: Int,
    val offset: Int,
)

private fun Ride.toDto(publicKeyHex: String) = RideDto(
    id = id,
    status = status.name,
    city = city,
    rideType = rideType,
    paymentMethod = paymentMethod,
    note = note,
    pickupLat = pickupLat,
    pickupLon = pickupLon,
    dropoffLat = dropoffLat,
    dropoffLon = dropoffLon,
    pickupName = pickupName,
    dropoffName = dropoffName,
    quote = QuoteDto(
        amountMinor = quoteAmountMinor,
        currency = quoteCurrency,
        mode = quoteMode,
        meteredCapMinor = quoteMeteredCapMinor,
        issuedAtEpochSec = quoteIssuedAtEpochSec,
        validUntilEpochSec = quoteValidUntilEpochSec,
        payload = quoteJson,
        signature = quoteSignature,
        publicKeyHex = publicKeyHex,
    ),
    cancelFeeMinor = cancelFeeMinor,
    createdAtEpochSec = createdAtEpochSec,
    updatedAtEpochSec = updatedAtEpochSec,
)

private fun err2(r: RideService.CreateResult.Invalid) = err(r.code, "Invalid ride request: ${r.field}")

fun Route.rideRoutes(service: AuthService, rides: RideService, publicKeyHex: String) {
    post("/v1/rides") {
        call.withUser(service) { user ->
            val req = call.receive<RideCreateRequest>()
            when (val r = rides.create(user, RideService.CreateRequest(
                pickupLat = req.pickupLat, pickupLon = req.pickupLon,
                dropoffLat = req.dropoffLat, dropoffLon = req.dropoffLon,
                city = req.city, rideType = req.rideType,
                paymentMethod = req.paymentMethod, note = req.note,
                pickupName = req.pickupName, dropoffName = req.dropoffName,
            ))) {
                is RideService.CreateResult.Created ->
                    call.respond(HttpStatusCode.Created, r.ride.toDto(publicKeyHex))
                is RideService.CreateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err2(r))
                is RideService.CreateResult.Forbidden ->
                    call.respond(HttpStatusCode.Forbidden, err(r.code, "Only riders can book rides"))
            }
        }
    }
    get("/v1/rides") {
        call.withUser(service) { user ->
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            if (limit !in 1..100 || offset < 0) {
                call.respond(HttpStatusCode.BadRequest, err("invalid_pagination", "limit must be in 1..100, offset >= 0"))
                return@withUser
            }
            val list = rides.listForRider(user.id, limit, offset).map { it.toDto(publicKeyHex) }
            call.respond(HttpStatusCode.OK, RideListDto(rides = list, limit = limit, offset = offset))
        }
    }
    get("/v1/rides/{id}") {
        call.withUser(service) { user ->
            val id = call.parameters["id"] ?: ""
            val ride = rides.getForRider(user.id, id)
            if (ride == null) {
                call.respond(HttpStatusCode.NotFound, err("ride_not_found", "Ride not found"))
            } else {
                call.respond(HttpStatusCode.OK, ride.toDto(publicKeyHex))
            }
        }
    }
    post("/v1/rides/{id}/cancel") {
        call.withUser(service) { user ->
            val id = call.parameters["id"] ?: ""
            when (val r = rides.cancel(user.id, id)) {
                is RideService.CancelResult.Cancelled ->
                    call.respond(HttpStatusCode.OK, r.ride.toDto(publicKeyHex))
                is RideService.CancelResult.Error ->
                    call.respond(
                        HttpStatusCode.fromValue(r.status),
                        err(r.code, "Cancellation refused: ${r.code}"),
                    )
            }
        }
    }
}

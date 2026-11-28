package com.ridehailing.backend.rides

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.withUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P4-2 ride routes (rider booking happy path):
 * - POST /v1/rides - RIDER role only (403 booking_role_required otherwise): creates the
 *   trip in REQUESTED with the server-SIGNED final quote (spec §pricing).
 * - GET /v1/rides - the rider's own rides, newest first.
 * - GET /v1/rides/{id} - rider-owned (404 for unknown AND not-owned: no existence leak).
 * - POST /v1/rides/{id}/cancel - P8-1: ONE route, role-dispatched. Riders cancel their
 *   own REQUESTED ride (fee-free inside the configured free window, charged outside) or
 *   an ACCEPTED/ARRIVED ride at the always-applied fee; DRIVER tokens are dispatched to
 *   the driver flow (respondDriverCancel in DriverRoutes.kt). The dispatch at the route
 *   entry + the service-level role guards keep the two flows separate; see
 *   DriverRoutes.kt for why the driver cancel SHARES this registration (single-winner
 *   Ktor resolution).
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
    /** P7-6: optional promo code - the server validates it and discounts the CHARGE. */
    val promoCode: String? = null,
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
    /** P7-6: promo applied at booking (null = full fare). */
    val promoCode: String? = null,
    /** P7-6: platform-side reduction of the signed fare (minor units). */
    val discountMinor: Long = 0,
    /** The amount actually charged = quote.amountMinor - discountMinor. */
    val chargeAmountMinor: Long,
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
    promoCode = promoCode,
    discountMinor = discountMinor,
    chargeAmountMinor = chargeAmountMinor,
)

private fun err2(r: RideService.CreateResult.Invalid) = err(r.code, "Invalid ride request: ${r.field}")

/** P8-1 decode outcome for the OPTIONAL cancel body (rider and driver flows). */
private sealed class CancelBody {
    data class Ok(val reason: String?) : CancelBody()
    data class Bad(val message: String) : CancelBody()
}

/**
 * P8-1 optional cancel body decode (manual JsonObject, promos pattern): the body
 * may be ABSENT entirely (the P4-2 no-body rider cancel keeps working); when
 * present it must be a JSON object whose optional `reason` is a string. An
 * explicit "reason": null folds into absent (round-36 promo-currency lesson:
 * JsonNull IS a JsonPrimitive with isString=false). Blank reason strings are
 * folded to absent by the SERVICE trim (create() note pattern).
 */
private suspend fun ApplicationCall.decodeCancelBody(): CancelBody {
    val raw = receiveText()
    if (raw.isBlank()) return CancelBody.Ok(null)
    val obj = try {
        Json.parseToJsonElement(raw)
    } catch (_: Exception) {
        return CancelBody.Bad("cancel body must be a JSON object with an optional string reason")
    }
    if (obj !is JsonObject) return CancelBody.Bad("cancel body must be a JSON object with an optional string reason")
    return when (val r = obj["reason"]) {
        null, is JsonNull -> CancelBody.Ok(null)
        is JsonPrimitive -> if (r.isString) CancelBody.Ok(r.content) else CancelBody.Bad("reason must be a string")
        else -> CancelBody.Bad("reason must be a string")
    }
}

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
                promoCode = req.promoCode,
            ))) {
                is RideService.CreateResult.Created ->
                    call.respond(HttpStatusCode.Created, r.ride.toDto(publicKeyHex))
                is RideService.CreateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, err2(r))
                is RideService.CreateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, err(r.code, "Promo code not found"))
                is RideService.CreateResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, err(r.code, "Ride request cannot be fulfilled"))
                is RideService.CreateResult.Forbidden ->
                    call.respond(HttpStatusCode.Forbidden, err(r.code, "Only riders can book rides"))
            }
        }
    }
    get("/v1/rides") {
        call.withUser(service) { user ->
            val limitRaw = call.request.queryParameters["limit"]
            val offsetRaw = call.request.queryParameters["offset"]
            val limit = limitRaw?.toIntOrNull() ?: 20
            val offset = offsetRaw?.toIntOrNull() ?: 0
            // ST201 (round-11 contract run): the schema declares limit/offset integers -
            // a PRESENT-but-malformed value ("?offset=null") must be 400, not silently
            // defaulted to the schema-violating 200 the fuzzer caught.
            val malformed = (limitRaw != null && limitRaw.toIntOrNull() == null) ||
                (offsetRaw != null && offsetRaw.toIntOrNull() == null)
            if (malformed || limit !in 1..100 || offset < 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    err("invalid_pagination", "limit must be an integer in 1..100, offset must be an integer >= 0"),
                )
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
            // P8-1: the body is OPTIONAL - absent entirely, {"reason":"..."} or
            // {"reason":null} all decode (see decodeCancelBody).
            val body = call.decodeCancelBody()
            if (body is CancelBody.Bad) {
                call.respond(HttpStatusCode.BadRequest, err("invalid_body", body.message))
                return@withUser
            }
            val reason = (body as CancelBody.Ok).reason
            // P8-1: ONE route, role-dispatched (Ktor resolves the FIRST route at equal
            // quality - a second registration in DriverRoutes would be dead code): a
            // DRIVER token goes to the driver flow, everything else to the rider flow.
            // Defense in depth: RideService.driverCancel 403s non-drivers and the rider
            // cancel is riderId-scoped, so a role can never act on the other's flow.
            if (user.role == Role.DRIVER) {
                respondDriverCancel(call, rides, user, id, reason)
            } else {
                when (val r = rides.cancel(user.id, id, reason)) {
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
}

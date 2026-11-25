package com.ridehailing.backend.rides

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.withUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * P5 driver routes (spec delivery plan 5 - driver app flow):
 * - POST /v1/driver/duty - DRIVER only; toggles the per-process duty registry with the
 *   driver's last position (audited; persistence lands with Phase 7 store work).
 * - GET /v1/driver/rides/available - DRIVER on duty; REQUESTED rides closest first.
 * - GET /v1/driver/rides - rides assigned to THIS driver, newest first.
 * - POST /v1/rides/{id}/accept|arrive|start|complete - driver-owned trip transitions;
 *   accept is the ATOMIC single-winner claim (409 already_accepted for the loser).
 *
 * DRIVER-DTO PARITY (spec verification item 2): driver DTOs carry NO rider PII - no
 * riderId, no contact data. The quote the rider agreed to rides verbatim (amount,
 * currency, mode) so the driver sees exactly what the rider sees about price.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class DriverDutyRequest(val online: Boolean, val lat: Double, val lon: Double)

@Serializable
data class DriverDutyDto(
    val online: Boolean,
    val lat: Double,
    val lon: Double,
    val updatedAtEpochSec: Long,
)

@Serializable
data class DriverQuoteDto(
    val amountMinor: Long,
    val currency: String,
    val mode: String,
    val meteredCapMinor: Long,
    val validUntilEpochSec: Long,
)

@Serializable
data class DriverRideDto(
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
    val quote: DriverQuoteDto,
    val cancelFeeMinor: Long,
    val createdAtEpochSec: Long,
    val updatedAtEpochSec: Long,
    val distanceMeters: Double? = null,
)

private fun Ride.toDriverDto(distanceMeters: Double? = null) = DriverRideDto(
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
    quote = DriverQuoteDto(
        amountMinor = quoteAmountMinor,
        currency = quoteCurrency,
        mode = quoteMode,
        meteredCapMinor = quoteMeteredCapMinor,
        validUntilEpochSec = quoteValidUntilEpochSec,
    ),
    cancelFeeMinor = cancelFeeMinor,
    createdAtEpochSec = createdAtEpochSec,
    updatedAtEpochSec = updatedAtEpochSec,
    distanceMeters = distanceMeters,
)

@Serializable
data class DriverRideListDto(
    val rides: List<DriverRideDto>,
    val limit: Int,
    val offset: Int,
)

private suspend fun actionResult(call: io.ktor.server.application.ApplicationCall, r: RideService.DriverActionResult) {
    when (r) {
        is RideService.DriverActionResult.Ok -> call.respond(HttpStatusCode.OK, r.ride.toDriverDto())
        is RideService.DriverActionResult.Error ->
            call.respond(
                HttpStatusCode.fromValue(r.status),
                err(r.code, "Driver action refused: ${r.code}"),
            )
    }
}

fun Route.driverRoutes(service: AuthService, rides: RideService) {
    route("/v1/driver") {
        post("/duty") {
            call.withUser(service) { driver ->
                val req = call.receive<DriverDutyRequest>()
                when (val r = rides.setDuty(driver, req.online, req.lat, req.lon)) {
                    is RideService.DutyResult.Set ->
                        call.respond(
                            HttpStatusCode.OK,
                            DriverDutyDto(r.state.online, r.state.lat, r.state.lon, r.state.updatedAtEpochSec),
                        )
                    is RideService.DutyResult.Error ->
                        call.respond(HttpStatusCode.fromValue(r.status), err(r.code, "Duty refused: ${r.code}"))
                }
            }
        }
        get("/rides/available") {
            call.withUser(service) { driver ->
                val limitRaw = call.request.queryParameters["limit"]
                val limit = limitRaw?.toIntOrNull()
                if (limitRaw != null && (limit == null || limit !in 1..50)) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_pagination", "limit must be an integer in 1..50"))
                    return@withUser
                }
                when (val r = rides.availableRides(driver, limit ?: 20)) {
                    is RideService.AvailableResult.Ok ->
                        call.respond(
                            HttpStatusCode.OK,
                            DriverRideListDto(
                                rides = r.rides.map { it.ride.toDriverDto(it.distanceMeters) },
                                limit = limit ?: 20,
                                offset = 0,
                            ),
                        )
                    is RideService.AvailableResult.Error ->
                        call.respond(HttpStatusCode.fromValue(r.status), err(r.code, "Available rides refused: ${r.code}"))
                }
            }
        }
        get("/rides") {
            call.withUser(service) { driver ->
                val limitRaw = call.request.queryParameters["limit"]
                val offsetRaw = call.request.queryParameters["offset"]
                val limit = limitRaw?.toIntOrNull()
                val offset = offsetRaw?.toIntOrNull()
                val lim = limit ?: 20
                val off = offset ?: 0
                val malformed = (limitRaw != null && limit == null) || (offsetRaw != null && offset == null)
                if (malformed || (limitRaw != null && lim !in 1..100) || (offsetRaw != null && off < 0)) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_pagination", "limit must be an integer in 1..100, offset must be an integer >= 0"))
                    return@withUser
                }
                val list = rides.forDriver(driver.id, lim, off)
                call.respond(HttpStatusCode.OK, DriverRideListDto(list.map { it.toDriverDto() }, lim, off))
            }
        }
    }
    post("/v1/rides/{id}/accept") {
        call.withUser(service) { driver ->
            actionResult(call, rides.accept(driver, call.parameters["id"] ?: ""))
        }
    }
    post("/v1/rides/{id}/arrive") {
        call.withUser(service) { driver ->
            actionResult(call, rides.driverAction(driver, call.parameters["id"] ?: "", RideStatus.ARRIVED))
        }
    }
    post("/v1/rides/{id}/start") {
        call.withUser(service) { driver ->
            actionResult(call, rides.driverAction(driver, call.parameters["id"] ?: "", RideStatus.IN_PROGRESS))
        }
    }
    post("/v1/rides/{id}/complete") {
        call.withUser(service) { driver ->
            actionResult(call, rides.driverAction(driver, call.parameters["id"] ?: "", RideStatus.COMPLETED))
        }
    }
}

package com.ridehailing.backend.rides

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Phase 6 admin live map routes (P6-4, spec "Live map: duty drivers + active trips"):
 * - GET /v1/admin/live (TRIPS_READ) - the on-duty drivers (duty registry: position
 *   from the driver's own duty toggle, v1 honest scope - no GPS stream until the
 *   location work) + the ACTIVE trips (REQUESTED/ACCEPTED/ARRIVED/IN_PROGRESS)
 *   plotted at their pickup coordinates.
 * - MESH_READ note: the spec gates the mesh-origin view by MESH_READ; mesh-originated
 *   rides land with Phase 10 (every trip answers origin="online" until then), so the
 *   endpoint is TRIPS_READ-only in v1 and the mesh filter is documented honestly here
 *   and in the OpenAPI description.
 * Read-only by design: the snapshot feeds map markers, never state changes.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class AdminLiveDriverDto(
    val driverId: String,
    /** Duty-registry position (the driver's own duty-toggle coordinates). */
    val lat: Double,
    val lon: Double,
    /** Registry only holds online drivers; kept explicit for forward compatibility. */
    val online: Boolean = true,
    val updatedAtEpochSec: Long,
)

@Serializable
data class AdminLiveTripDto(
    val id: String,
    val status: String,
    val city: String,
    val riderId: String,
    val driverId: String?,
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    /** Spec's admin-only origin field; "offline-mesh" lands with Phase 10 (mesh rides). */
    val origin: String = "online",
)

@Serializable
data class AdminLiveDto(
    val drivers: List<AdminLiveDriverDto>,
    val trips: List<AdminLiveTripDto>,
)

/** The statuses that belong on the live map (everything else is historical). */
private val ACTIVE_TRIP_STATUSES = listOf(
    RideStatus.REQUESTED,
    RideStatus.ACCEPTED,
    RideStatus.ARRIVED,
    RideStatus.IN_PROGRESS,
)

fun Route.adminLiveRoutes(service: AuthService, rideService: RideService, store: RideStore) {
    route("/v1/admin/live") {
        get {
            call.withPermission(service, Perm.TRIPS_READ) { _ ->
                call.respondLiveSnapshot(rideService, store)
            }
        }
    }
}

/** 200 with the drivers+trips snapshot; malformed pagination is not applicable here (no paging - bounded by 200/status). */
private suspend fun ApplicationCall.respondLiveSnapshot(rideService: RideService, store: RideStore) {
    val drivers = rideService.dutySnapshot().map { (driverId, state) ->
        AdminLiveDriverDto(
            driverId = driverId,
            lat = state.lat,
            lon = state.lon,
            online = state.online,
            updatedAtEpochSec = state.updatedAtEpochSec,
        )
    }
    val trips = ACTIVE_TRIP_STATUSES.flatMap { status -> store.listAll(status, null, 200, 0) }
        .map { ride ->
            AdminLiveTripDto(
                id = ride.id,
                status = ride.status.name,
                city = ride.city,
                riderId = ride.riderId,
                driverId = ride.driverId,
                pickupLat = ride.pickupLat,
                pickupLon = ride.pickupLon,
                dropoffLat = ride.dropoffLat,
                dropoffLon = ride.dropoffLon,
                origin = "online",
            )
        }
    respond(HttpStatusCode.OK, AdminLiveDto(drivers = drivers, trips = trips))
}

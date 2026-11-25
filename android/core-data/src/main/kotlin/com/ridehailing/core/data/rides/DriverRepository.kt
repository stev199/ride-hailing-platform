package com.ridehailing.core.data.rides

import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.network.DriverDutyDto
import com.ridehailing.core.data.network.DriverDutyRequestDto
import com.ridehailing.core.data.network.DriverRideDto
import com.ridehailing.core.data.network.DriverRideListDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/** Driver-side trip actions (state machine lives server-side; the app only asks). */
enum class DriverAction { ARRIVE, START, COMPLETE }

/**
 * P5 driver repository: duty toggle, dispatch board polling, the single-winner claim
 * and the trip actions. Every call is a Result - failures carry the VERBATIM server
 * error (code + message, B-04) or a typed local reason ("auth_required"). Offline is
 * honest: the duty state itself is server-side, so there is deliberately NO offline
 * duty cache - the toggle tells the truth about what the backend accepted.
 */
@Singleton
class DriverRepository @Inject constructor(
    @ApiClient private val http: HttpClient,
    private val auth: AuthRepository,
) {
    private suspend fun bearer(): String = auth.accessToken() ?: throw ApiRequestException(
        com.ridehailing.core.data.network.ApiError("auth_required", "Sign in first", null),
    )

    private suspend fun respondOrThrow(response: HttpResponse): HttpResponse =
        if (response.status.isSuccess()) response else throw ApiRequestException(
            com.ridehailing.core.data.network.apiErrorFrom(response),
        )

    suspend fun setDuty(online: Boolean, lat: Double, lon: Double): Result<DriverDutyDto> = runCatching {
        val response = http.post("/v1/driver/duty") {
            header("Authorization", "Bearer ${bearer()}")
            contentType(ContentType.Application.Json)
            setBody(DriverDutyRequestDto(online, lat, lon))
        }
        respondOrThrow(response).body<DriverDutyDto>()
    }

    /** Dispatch board: REQUESTED rides closest first; 409 duty_offline verbatim when off duty. */
    suspend fun availableRides(limit: Int = 20): Result<List<DriverRideDto>> = runCatching {
        val response = http.get("/v1/driver/rides/available?limit=$limit") {
            header("Authorization", "Bearer ${bearer()}")
        }
        respondOrThrow(response).body<DriverRideListDto>().rides
    }

    suspend fun myRides(limit: Int = 20, offset: Int = 0): Result<List<DriverRideDto>> = runCatching {
        val response = http.get("/v1/driver/rides?limit=$limit&offset=$offset") {
            header("Authorization", "Bearer ${bearer()}")
        }
        respondOrThrow(response).body<DriverRideListDto>().rides
    }

    suspend fun accept(rideId: String): Result<DriverRideDto> = runCatching {
        val response = http.post("/v1/rides/$rideId/accept") {
            header("Authorization", "Bearer ${bearer()}")
            contentType(ContentType.Application.Json)
        }
        respondOrThrow(response).body<DriverRideDto>()
    }

    suspend fun tripAction(rideId: String, action: DriverAction): Result<DriverRideDto> = runCatching {
        val response = http.post("/v1/rides/$rideId/${action.name.lowercase()}") {
            header("Authorization", "Bearer ${bearer()}")
            contentType(ContentType.Application.Json)
        }
        respondOrThrow(response).body<DriverRideDto>()
    }
}

package com.ridehailing.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auth contract DTOs - byte-mirror of the openapi (PasswordLoginRequest, TokenPairDto,
 * User, AuthSuccess, Refreshed, Error). Field names come from the wire; unknown keys are
 * ignored (forward-compatible, D46 pattern).
 */
@Serializable
data class PasswordLoginRequest(val identifier: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    @SerialName("accessExpiresAtEpochSec") val accessExpiresAtEpochSec: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val phone: String? = null,
    val email: String? = null,
    val displayName: String,
    val role: String,
    val status: String,
    val locale: String,
)

@Serializable
data class AuthSuccessDto(
    val user: UserDto,
    val tokens: TokenPairDto,
    val created: Boolean,
)

@Serializable
data class RefreshedDto(val tokens: TokenPairDto)

/** Error schema {code, message} - surfaced VERBATIM to the UI (server is the authority). */
@Serializable
data class ApiErrorDto(val code: String, val message: String)

/** App-side error the UI renders (code + message verbatim; status for diagnostics). */
data class ApiError(val code: String, val message: String, val httpStatus: Int? = null)

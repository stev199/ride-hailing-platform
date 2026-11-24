package com.ridehailing.backend.config

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ---------- DTOs (mirrored in docs/api/openapi.yaml) ----------

/**
 * Device-facing bundle. `config` is the EXACT canonical JSON string the signature
 * covers (D49 generator-precise: re-serializing a parsed object would not be
 * byte-identical, so the signed string rides the wire verbatim). The device verifies
 * signature = Ed25519(publicKey, "version|config|issuedAtEpochSec") and rejects
 * version <= its last accepted version (anti-rollback).
 */
@Serializable
data class ConfigBundleDto(
    val version: Long,
    val issuedAtEpochSec: Long,
    val config: String,
    val signature: String,
    val publicKey: String,
)

@Serializable
data class ConfigVersionDto(
    val version: Long,
    val issuedAtEpochSec: Long,
    val config: String,
    val signature: String,
    val changedBy: String,
    val change: String,
)

@Serializable
data class AdminConfigDto(
    val currentVersion: Long,
    val loginMethods: LoginMethodsStateDto,
    val history: List<ConfigVersionDto>,
)

@Serializable
data class LoginMethodsStateDto(
    val passwordLoginEnabled: Boolean,
    val otpAutoRegister: Boolean,
    val otpChannels: Map<String, ChannelStateDto>,
)

@Serializable
data class ChannelStateDto(val enabled: Boolean, val priority: Int, val devOnly: Boolean)

private fun ConfigService.VersionEntry.toDto() = ConfigVersionDto(
    version = version,
    issuedAtEpochSec = issuedAtEpochSec,
    config = configCanonicalJson,
    signature = signatureHex,
    changedBy = changedBy,
    change = change,
)

private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/**
 * P1-5a config routes. `/v1/config` is PUBLIC (devices fetch the bundle pre-login,
 * signature is the trust anchor). The admin pair sits behind CONFIG_READ / CONFIG_WRITE
 * (401 vs 403 never merged, B-04).
 */
fun Route.configRoutes(config: ConfigService, service: AuthService) {
    get("/v1/config") {
        val bundle = config.currentBundle()
        call.respond(
            HttpStatusCode.OK,
            ConfigBundleDto(
                version = bundle.version,
                issuedAtEpochSec = bundle.issuedAtSec,
                config = bundle.configJson,
                signature = bundle.signature.toHex(),
                publicKey = config.publicKeyHex(),
            ),
        )
    }
    route("/v1/admin/config") {
        get("/login-methods") {
            call.withPermission(service, Perm.CONFIG_READ) {
                val bundle = config.currentBundle()
                val m = config.loginMethods
                call.respond(
                    HttpStatusCode.OK,
                    AdminConfigDto(
                        currentVersion = bundle.version,
                        loginMethods = LoginMethodsStateDto(
                            passwordLoginEnabled = m.passwordLoginEnabled,
                            otpAutoRegister = m.otpAutoRegister,
                            otpChannels = m.otpChannels.entries.associate { (ch, cfg) ->
                                ch.name to ChannelStateDto(cfg.enabled, cfg.priority, cfg.devOnly)
                            },
                        ),
                        history = config.historySnapshot().map { it.toDto() },
                    ),
                )
            }
        }
        patch("/login-methods") {
            call.withPermission(service, Perm.CONFIG_WRITE) { actor ->
                val patch = call.receive<LoginMethodsPatch>()
                when (val r = config.updateLoginMethods(actor.id, patch)) {
                    is ConfigService.UpdateResult.Updated -> call.respond(HttpStatusCode.OK, r.entry.toDto())
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(r.code, "Validation failed: ${r.field}"))
                }
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

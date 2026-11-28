package com.ridehailing.backend.wallet

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.config.ConfigService
import com.ridehailing.backend.config.WalletConfig
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * P7-1 wallet config routes (fares pattern): GET (CONFIG_READ) + PATCH
 * (CONFIG_WRITE, D75 FULL-REPLACE - the admin sends the edited document, the
 * server validates it completely; invalid configs never touch live state and
 * never burn a version). Every accepted patch bumps the signed bundle version
 * and appends a `config.wallet.updated` audit event.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class WalletConfigDto(
    val transferFeeBps: Int,
    val minTransferMinor: Long,
    val dailyTransferLimitMinor: Long,
) {
    fun toConfig() = WalletConfig(transferFeeBps, minTransferMinor, dailyTransferLimitMinor)
}

private fun WalletConfig.toDto() = WalletConfigDto(transferFeeBps, minTransferMinor, dailyTransferLimitMinor)

@Serializable
data class WalletConfigUpdatedDto(
    val version: Long,
    val config: WalletConfigDto,
)

fun Route.walletConfigRoutes(service: AuthService, config: ConfigService) {
    route("/v1/admin/config/wallet") {
        get {
            call.withPermission(service, Perm.CONFIG_READ) { _ ->
                call.respond(HttpStatusCode.OK, config.walletConfig.toDto())
            }
        }
        patch {
            call.withPermission(service, Perm.CONFIG_WRITE) { user ->
                val obj = call.receive<kotlinx.serialization.json.JsonObject>()
                // D49: integral floats (0.0) are schema-valid integers - decode leniently.
                fun integral(name: String): Long? {
                    val v = obj[name] as? kotlinx.serialization.json.JsonPrimitive ?: return null
                    if (v.isString) return null
                    return v.longOrNull ?: v.doubleOrNull
        ?.takeIf { it == kotlin.math.floor(it) && it.isFinite() && kotlin.math.abs(it) <= 9.0e15 }  // beyond the double exact-integer range the value is either int64-overflowing or precision-lost
        ?.toLong()
                }
                val fee = integral("transferFeeBps")
                    ?: return@withPermission call.respond(HttpStatusCode.BadRequest, err("invalid_body", "transferFeeBps must be an integer"))
                val min = integral("minTransferMinor")
                    ?: return@withPermission call.respond(HttpStatusCode.BadRequest, err("invalid_body", "minTransferMinor must be an integer"))
                val daily = integral("dailyTransferLimitMinor")
                    ?: return@withPermission call.respond(HttpStatusCode.BadRequest, err("invalid_body", "dailyTransferLimitMinor must be an integer"))
                if (fee !in 0..Int.MAX_VALUE || min < 1 || daily < 1) {
                    call.respond(HttpStatusCode.BadRequest, err("wallet", "transferFeeBps: 0..1000 required; minTransferMinor/dailyTransferLimitMinor: >= 1 required"))
                    return@withPermission
                }
                when (val res = config.updateWallet(user.id, WalletConfig(fee.toInt(), min, daily))) {
                    is ConfigService.UpdateResult.Updated ->
                        call.respond(HttpStatusCode.OK, WalletConfigUpdatedDto(version = res.entry.version, config = config.walletConfig.toDto()))
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(res.code, res.code.replace('_', ' ')))
                }
            }
        }
    }
}

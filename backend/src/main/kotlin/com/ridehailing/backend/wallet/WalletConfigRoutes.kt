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
                val body = call.receive<WalletConfigDto>()
                when (val res = config.updateWallet(user.id, body.toConfig())) {
                    is ConfigService.UpdateResult.Updated ->
                        call.respond(HttpStatusCode.OK, WalletConfigUpdatedDto(version = res.entry.version, config = config.walletConfig.toDto()))
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err(res.code, res.code.replace('_', ' ')))
                }
            }
        }
    }
}

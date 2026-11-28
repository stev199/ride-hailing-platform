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

/**
 * P7-6 promo config routes (fares/wallet pattern): GET (CONFIG_READ) + PATCH
 * (CONFIG_WRITE, D75 FULL-REPLACE - the admin sends the edited document, the
 * server validates it completely; invalid configs never touch live state and
 * never burn a version). Every accepted patch bumps the signed bundle version
 * and appends a `config.promos.updated` audit event. The PATCH decodes through
 * the typed PromoConfig DTO (code strings cannot be fuzzed into primitives) and
 * validation errors come back VERBATIM in one message.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class PromoConfigUpdatedDto(
    val version: Long,
    val config: PromoConfig,
)

fun Route.promoConfigRoutes(service: AuthService, config: ConfigService) {
    route("/v1/admin/config/promos") {
        get {
            call.withPermission(service, Perm.CONFIG_READ) { _ ->
                call.respond(HttpStatusCode.OK, config.promos)
            }
        }
        patch {
            call.withPermission(service, Perm.CONFIG_WRITE) { user ->
                val body = try {
                    call.receive<PromoConfig>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_body", "promos must be {\"codes\":[...]}"))
                    return@withPermission
                }
                when (val res = config.updatePromos(user.id, body)) {
                    is ConfigService.UpdateResult.Updated ->
                        call.respond(HttpStatusCode.OK, PromoConfigUpdatedDto(version = res.entry.version, config = config.promos))
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_promos", res.code))
                }
            }
        }
    }
}

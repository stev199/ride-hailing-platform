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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * P7-6 promo config routes (fares/wallet pattern): GET (CONFIG_READ) + PATCH
 * (CONFIG_WRITE, D75 FULL-REPLACE - the admin sends the edited document, the
 * server validates it completely; invalid configs never touch live state and
 * never burn a version). Every accepted patch bumps the signed bundle version
 * and appends a `config.promos.updated` audit event.
 *
 * Round-30 contract lessons (schemathesis):
 * - ST201: the PATCH decodes through a JsonObject and the schema-required `codes`
 *   key must be PRESENT and an array - kotlinx defaults never absorb a missing key
 *   (the round-11 fares partial-reset class).
 * - ST202/D49: JSON Schema treats 1.0 as a valid integer - epoch/limit fields decode
 *   with integral-float leniency exactly like the wallet bodies (0.0 == 0 accepted,
 *   0.5 rejected). `value` is a real number (PERCENT fractions) and stays Double.
 *
 * Round-33 contract lessons (schemathesis run 46117305495):
 * - ST201+ST108 (QK4V6R/XT4gZF): the schema maximum perUserLimit <= 1000 is now
 *   enforced in PromoConfig.validationErrors (the single write path into the store,
 *   so GET can never echo an out-of-bounds value).
 * - ST202 (eDh6YV): the AMOUNT money cap reads the DECIMAL FORM with boundary-double
 *   leniency (PromoCodeConfig.amountValueInMoneyCap): shortest-repr floats one step
 *   above the cap (1.0000000000000001e+18) are the cap at double precision and clamp
 *   to it; integer text above the cap keeps the exact round-24c guard.
 *
 * Round-36 contract lesson (schemathesis runs 46126361252/46131229495, cases
 * Ze60ry/3Ce4Ls): an EXPLICIT "currency": null reaches decodedCode() as JsonNull,
 * which IS a JsonPrimitive with isString=false - the old `null ->` arm only caught
 * the ABSENT key, so schema-valid currency:null codes 400'd with invalid_body.
 * JsonNull is now folded into the schema-valid null arm (the AMOUNT then-branch
 * keeps pinning currency to type:string, so AMOUNT+null stays schema-invalid).
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/** D49 integral-float leniency: 1.0 -> 1, 1.5 -> null (schema-invalid anyway). */
private fun JsonObject.integralLong(name: String): Long? {
    val v = this[name] as? JsonPrimitive ?: return null
    if (v.isString) return null
    return v.longOrNull ?: v.doubleOrNull
        ?.takeIf { it == kotlin.math.floor(it) && it.isFinite() && kotlin.math.abs(it) <= 9.0e15 }
        ?.toLong()
}

private val KNOWN_CODE_KEYS = setOf("code", "kind", "value", "currency", "validFromEpochSec", "validUntilEpochSec", "perUserLimit", "active")

private fun JsonObject.decodedCode(): PromoCodeConfig? {
    // additionalProperties: false - unknown keys are schema violations -> reject.
    if (keys.any { it !in KNOWN_CODE_KEYS }) return null
    val code = (this["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val kind = (this["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val currency = when (val c = this["currency"]) {
        // Round-36: ABSENT key returns null, but an EXPLICIT JSON null arrives as
        // JsonNull (a JsonPrimitive, isString=false) - both are schema-valid
        // ([string, 'null']) and must decode to null currency.
        null, is JsonNull -> null
        is JsonPrimitive -> if (c.isString) c.content else return null // number/bool -> schema-invalid
        else -> return null // arrays/objects -> schema-invalid
    }
    val active = when (val a = this["active"]) {
        null -> true // absent = default (schema default true)
        is JsonPrimitive -> a.booleanOrNull ?: return null // non-boolean -> schema-invalid, reject
        else -> return null // objects/arrays -> schema-invalid, reject
    }
    // Round-31 ST202 parity: presence-strict defaults - a PRESENT integral field that
    // fails the integral decode (1.5) is schema-invalid and must reject, never default.
    val validUntil = integralLong("validUntilEpochSec") ?: return null
    val validFrom = if (containsKey("validFromEpochSec")) integralLong("validFromEpochSec") ?: return null else 0L
    val perUser = (integralLong("perUserLimit") ?: return null).let {
        // Note: the schema maximum (<= 1000) is enforced by PromoConfig.validationErrors
        // (invalid_promos) so the round-33 rejection rides the documented config code.
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) return null else it.toInt()
    }
    // Strings are schema-invalid for `value` (type: number) and rejected.
    val valueEl = this["value"] as? JsonPrimitive ?: return null
    if (valueEl.isString) return null
    val valueNum = if (kind == PromoCodeConfig.KIND_AMOUNT) {
        // Round-33 (CI eDh6YV): the 1e18 money cap is decided on the DECIMAL FORM with
        // boundary-double leniency - see PromoCodeConfig.amountValueInMoneyCap. Band
        // values are returned CLAMPED to the cap, so the store stays strictly in-bounds.
        PromoCodeConfig.amountValueInMoneyCap(valueEl.content) ?: return null
    } else {
        valueEl.doubleOrNull?.takeIf { it.isFinite() } ?: return null
    }
    return PromoCodeConfig(
        code = code, kind = kind, value = valueNum, currency = currency,
        validFromEpochSec = validFrom, validUntilEpochSec = validUntil,
        perUserLimit = perUser, active = active,
    )
}

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
                val obj = call.receive<JsonObject>()
                val codesEl = obj["codes"] as? JsonArray
                    ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "codes is required and must be an array"),
                    )
                val decoded = ArrayList<PromoCodeConfig>(codesEl.size)
                for (entry in codesEl) {
                    val asObj = entry as? JsonObject ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "codes entries do not match the PromoCode schema"),
                    )
                    val c = asObj.decodedCode() ?: return@withPermission call.respond(
                        HttpStatusCode.BadRequest,
                        err("invalid_body", "codes entries do not match the PromoCode schema"),
                    )
                    decoded.add(c)
                }
                when (val res = config.updatePromos(user.id, PromoConfig(decoded))) {
                    is ConfigService.UpdateResult.Updated ->
                        call.respond(HttpStatusCode.OK, PromoConfigUpdatedDto(version = res.entry.version, config = config.promos))
                    is ConfigService.UpdateResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_promos", res.code))
                }
            }
        }
    }
}

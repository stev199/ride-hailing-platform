package com.ridehailing.backend.wallet

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * P7-1 wallet routes (spec §wallet: balance, transfer by phone, history; limits/fees
 * from admin config). Owner-scoped surfaces use withUser (any authenticated account);
 * the admin credit is MONEY_WRITE (platform-funded top-up = the v1 top-up path).
 * Money conservation lives in the ledger; these routes only translate outcomes.
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

@Serializable
data class WalletBalancesDto(
    /** Present currencies -> minor units (v1: POINTS; CASH accrues with P7-2). */
    val balances: Map<String, Long>,
    /** Amount currently moved into the hold wallet (booking holds, P7-2). */
    val heldMinor: Long = 0,
)

@Serializable
data class WalletHistoryItemDto(
    val entryId: String,
    val transactionId: String,
    val amountMinor: Long,
    val direction: String,
    val reason: String,
    val refType: String?,
    val refId: String?,
    val createdAtEpochSec: Long,
)

@Serializable
data class WalletHistoryDto(
    val items: List<WalletHistoryItemDto>,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class WalletTransferRequest(val toPhone: String, val amountMinor: Long, val note: String? = null)

@Serializable
data class WalletTransferDto(val feeMinor: Long, val receiverGotMinor: Long, val balanceMinor: Long)

@Serializable
data class AdminWalletCreditRequest(val amountMinor: Long, val reason: String)

@Serializable
data class AdminWalletCreditDto(val balances: Map<String, Long>)

/** Pagination contract copied from AdminTripRoutes (D49): present-but-malformed = 400. */
private suspend fun ApplicationCall.paginationOr400(defaultLimit: Int, maxLimit: Int): Pair<Int, Int>? {
    suspend fun bad(detail: String): Nothing? {
        respond(HttpStatusCode.BadRequest, err("invalid_pagination", detail))
        return null
    }
    val rawLimit = request.queryParameters["limit"]
    val rawOffset = request.queryParameters["offset"]
    val limit = rawLimit?.toIntOrNull() ?: if (rawLimit != null) return bad("limit must be an integer in 1..$maxLimit") else defaultLimit
    val offset = rawOffset?.toIntOrNull() ?: if (rawOffset != null) return bad("offset must be a non-negative integer") else 0
    if (limit < 1 || limit > maxLimit || offset < 0) return bad("limit must be 1..$maxLimit, offset must be >= 0")
    return limit to offset
}

private fun invalid(res: WalletService.TransferResult.Invalid): HttpStatusCode = when (res.code) {
    "unknown_user" -> HttpStatusCode.NotFound
    "insufficient_balance", "daily_limit_exceeded" -> HttpStatusCode.Conflict
    else -> HttpStatusCode.BadRequest
}

/** Local bearer guard (same shape as auth withUser; internal import is flaky cross-package). */
private suspend fun ApplicationCall.withUser(service: AuthService, handler: suspend (com.ridehailing.backend.auth.User) -> Unit) {
    val header = request.header(io.ktor.http.HttpHeaders.Authorization) ?: ""
    val token = header.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
    val user = token?.let { service.currentUser(it) }
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, err("unauthorized", "Missing or invalid access token"))
    } else {
        handler(user)
    }
}

fun Route.walletRoutes(service: AuthService, wallet: WalletService) {
    route("/v1/wallet") {
        get {
            call.withUser(service) { user ->
                call.respond(
                    HttpStatusCode.OK,
                    WalletBalancesDto(balances = wallet.balances(user.id), heldMinor = wallet.held(user.id)),
                )
            }
        }
        get("/transactions") {
            call.withUser(service) { user ->
                val (limit, offset) = call.paginationOr400(defaultLimit = 50, maxLimit = 200)
                    ?: return@withUser
                val items = wallet.history(user.id, limit, offset).map {
                    WalletHistoryItemDto(
                        entryId = it.entryId,
                        transactionId = it.transactionId,
                        amountMinor = it.amountMinor,
                        direction = it.direction.name,
                        reason = it.reason,
                        refType = it.refType,
                        refId = it.refId,
                        createdAtEpochSec = it.createdAtEpochSec,
                    )
                }
                call.respond(HttpStatusCode.OK, WalletHistoryDto(items = items, limit = limit, offset = offset))
            }
        }
        post("/transfer") {
            call.withUser(service) { user ->
                val body = call.receive<WalletTransferRequest>()
                when (val res = wallet.transferPoints(user, body.toPhone, body.amountMinor, body.note)) {
                    is WalletService.TransferResult.Done -> {
                        val balances = wallet.balances(user.id)
                        call.respond(
                            HttpStatusCode.OK,
                            WalletTransferDto(
                                feeMinor = res.feeMinor,
                                receiverGotMinor = res.receiverGotMinor,
                                balanceMinor = balances[WalletService.POINTS] ?: 0L,
                            ),
                        )
                    }
                    is WalletService.TransferResult.Invalid ->
                        call.respond(invalid(res), err(res.code, res.code.replace('_', ' ')))
                }
            }
        }
    }
    route("/v1/admin/users/{id}/wallet-credit") {
        post {
            call.withPermission(service, Perm.MONEY_WRITE) { _ ->
                val body = call.receive<AdminWalletCreditRequest>()
                if (body.reason.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_reason", "reason is required for admin credit"))
                    return@withPermission
                }
                when (val res = wallet.adminCredit(call.parameters["id"] ?: "", body.amountMinor, body.reason)) {
                    is WalletService.TransferResult.Done ->
                        call.respond(HttpStatusCode.OK, AdminWalletCreditDto(balances = wallet.balances(call.parameters["id"] ?: "")))
                    is WalletService.TransferResult.Invalid ->
                        call.respond(invalid(res), err(res.code, res.code.replace('_', ' ')))
                }
            }
        }
    }
}

/** Role guard used nowhere yet - keeps the import surface explicit for MONEY checks. */
@Suppress("unused")
private val moneyRoles = setOf(Role.ADMIN_OPS, Role.ADMIN_FINANCE)

package com.ridehailing.backend.verification

import com.ridehailing.backend.ErrorDto
import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.withUser
import com.ridehailing.backend.rbac.Perm
import com.ridehailing.backend.rbac.withPermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Phase 6 verification center routes (P6-1):
 * Driver surface:
 * - POST /v1/verification       (any authenticated user; service enforces DRIVER-only)
 * - GET  /v1/verification/me    (the caller's latest submission, 200/404)
 * Admin surface (verification center):
 * - GET  /v1/admin/verification            (VERIFICATION_READ, status filter + pagination)
 * - GET  /v1/admin/verification/{id}       (VERIFICATION_READ)
 * - POST /v1/admin/verification/{id}/approve (VERIFICATION_WRITE, audited)
 * - POST /v1/admin/verification/{id}/reject  (VERIFICATION_WRITE + reason, audited)
 *
 * Document IMAGE upload + encrypted storage is the S-03 slice - v1 stores the
 * declared document NAMES only (see Verification.kt for the honest scope note).
 */
private fun err(code: String, message: String) = ErrorDto(code = code, message = message)

/** Pagination contract copied from AdminRoutes (D49): present-but-malformed = 400. */
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

@Serializable
data class VerificationSubmitRequest(
    val licenseNumber: String,
    val vehiclePlate: String,
    /** Document NAMES in the declared set ("license_front", "insurance", ...). */
    val declaredDocs: List<String>,
)

@Serializable
data class VerificationRejectRequest(val reason: String)

@Serializable
data class VerificationSubmissionDto(
    val id: String,
    val userId: String,
    val licenseNumber: String,
    val vehiclePlate: String,
    val declaredDocs: List<String>,
    val status: String,
    val submittedAtEpochSec: Long,
    val reviewedAtEpochSec: Long? = null,
    val reviewerId: String? = null,
    val rejectReason: String? = null,
)

@Serializable
data class VerificationListDto(
    val submissions: List<VerificationSubmissionDto>,
    val limit: Int,
    val offset: Int,
)

private fun VerificationSubmission.toDto() = VerificationSubmissionDto(
    id = id,
    userId = userId,
    licenseNumber = licenseNumber,
    vehiclePlate = vehiclePlate,
    declaredDocs = declaredDocs,
    status = status.name,
    submittedAtEpochSec = submittedAtEpochSec,
    reviewedAtEpochSec = reviewedAtEpochSec,
    reviewerId = reviewerId,
    rejectReason = rejectReason,
)

private fun parseStatus(raw: String): VerificationStatus? =
    VerificationStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

fun Route.verificationRoutes(service: AuthService, verification: VerificationService) {
    route("/v1/verification") {
        post {
            // Any AUTHENTICATED user may call; the service enforces DRIVER-only (403
            // role_not_driver). withPermission would be wrong here - RIDER/DRIVER hold
            // an empty admin permission set by design (driver blindness).
            call.withUser(service) { actor ->
                val body = call.receive<VerificationSubmitRequest>()
                when (val result = verification.submit(actor, VerificationService.SubmitFields(body.licenseNumber, body.vehiclePlate, body.declaredDocs))) {
                    is VerificationService.SubmitResult.Created ->
                        call.respond(HttpStatusCode.Created, result.submission.toDto())
                    VerificationService.SubmitResult.NotADriver ->
                        call.respond(HttpStatusCode.Forbidden, err("role_not_driver", "Only DRIVER accounts can submit verification"))
                    VerificationService.SubmitResult.AlreadyPending ->
                        call.respond(HttpStatusCode.Conflict, err("already_submitted", "A submission is already pending or approved"))
                    VerificationService.SubmitResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_verification", "licenseNumber (1..64), vehiclePlate (1..32) and 1..16 declaredDocs are required"))
                }
            }
        }
        get("/me") {
            call.withUser(service) { actor ->
                val latest = verification.latestFor(actor)
                if (latest == null) {
                    call.respond(HttpStatusCode.NotFound, err("no_submission", "No verification submission for this account"))
                } else {
                    call.respond(HttpStatusCode.OK, latest.toDto())
                }
            }
        }
    }
    route("/v1/admin/verification") {
        get {
            call.withPermission(service, Perm.VERIFICATION_READ) { _ ->
                val (limit, offset) = call.paginationOr400(defaultLimit = 50, maxLimit = 200)
                    ?: return@withPermission
                val status = call.request.queryParameters["status"]?.let { raw ->
                    parseStatus(raw) ?: run {
                        call.respond(HttpStatusCode.BadRequest, err("invalid_status", "Unknown verification status: $raw"))
                        return@withPermission
                    }
                }
                val rows = verification.queue(status, limit, offset)
                call.respond(HttpStatusCode.OK, VerificationListDto(submissions = rows.map { it.toDto() }, limit = limit, offset = offset))
            }
        }
        get("/{id}") {
            call.withPermission(service, Perm.VERIFICATION_READ) { _ ->
                val submission = verification.byId(call.parameters["id"] ?: "")
                if (submission == null) {
                    call.respond(HttpStatusCode.NotFound, err("submission_not_found", "No such verification submission"))
                } else {
                    call.respond(HttpStatusCode.OK, submission.toDto())
                }
            }
        }
        post("/{id}/approve") {
            call.withPermission(service, Perm.VERIFICATION_WRITE) { actor ->
                val id = call.parameters["id"] ?: ""
                when (val result = verification.approve(actor, id)) {
                    is VerificationService.ReviewResult.Reviewed ->
                        call.respond(HttpStatusCode.OK, result.submission.toDto())
                    VerificationService.ReviewResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, err("submission_not_found", "No such verification submission"))
                    VerificationService.ReviewResult.NotPending ->
                        call.respond(HttpStatusCode.Conflict, err("not_pending", "Only PENDING submissions can be reviewed"))
                    VerificationService.ReviewResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_verification", "Invalid review"))
                }
            }
        }
        post("/{id}/reject") {
            call.withPermission(service, Perm.VERIFICATION_WRITE) { actor ->
                val body = call.receive<VerificationRejectRequest>()
                when (val result = verification.reject(actor, call.parameters["id"] ?: "", body.reason)) {
                    is VerificationService.ReviewResult.Reviewed ->
                        call.respond(HttpStatusCode.OK, result.submission.toDto())
                    VerificationService.ReviewResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, err("submission_not_found", "No such verification submission"))
                    VerificationService.ReviewResult.NotPending ->
                        call.respond(HttpStatusCode.Conflict, err("not_pending", "Only PENDING submissions can be reviewed"))
                    VerificationService.ReviewResult.Invalid ->
                        call.respond(HttpStatusCode.BadRequest, err("invalid_reason", "A reject reason (1..512 chars) is required"))
                }
            }
        }
    }
}

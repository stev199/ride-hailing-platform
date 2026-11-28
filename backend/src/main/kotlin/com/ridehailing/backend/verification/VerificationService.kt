package com.ridehailing.backend.verification

import com.ridehailing.backend.auth.AuthService
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.lengthInCodePoints

/**
 * Verification workflow service (P6-1). Business rules the ROUTES do not know:
 *  - only an active DRIVER may submit (riders/admins 403 at the route, and the
 *    service re-checks so direct service use cannot bypass it);
 *  - one PENDING submission per driver - re-submitting while pending is 409;
 *  - an APPROVED submission is terminal (revocation = admin ban, not review);
 *  - only PENDING can transition to APPROVED/REJECTED (double-review = 409);
 *  - REJECT carries a reviewer reason (the driver sees it verbatim on /me);
 *  - every REVIEW action lands in the hash-chained audit store with a
 *    PII-minimal payload (ids + status + reason, never the license number).
 */
class VerificationService(
    private val store: VerificationStore,
    private val eventRecorder: AuthService.EventChainRecorder,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {

    data class SubmitFields(
        val licenseNumber: String,
        val vehiclePlate: String,
        val declaredDocs: List<String>,
    )

    sealed class SubmitResult {
        data class Created(val submission: VerificationSubmission) : SubmitResult()
        data object AlreadyPending : SubmitResult()
        data object NotADriver : SubmitResult()
        data object Invalid : SubmitResult()
    }

    sealed class ReviewResult {
        data class Reviewed(val submission: VerificationSubmission) : ReviewResult()
        data object NotFound : ReviewResult()
        data object NotPending : ReviewResult()
        data object Invalid : ReviewResult()
    }

    fun submit(actor: User, fields: SubmitFields): SubmitResult {
        if (actor.role != Role.DRIVER) return SubmitResult.NotADriver
        val license = fields.licenseNumber.trim()
        val plate = fields.vehiclePlate.trim()
        // Round-37: code points, not UTF-16 units (documented 1..64 / 1..32).
        if (license.isEmpty() || license.lengthInCodePoints() > 64 || plate.isEmpty() || plate.lengthInCodePoints() > 32) {
            return SubmitResult.Invalid
        }
        val docs = fields.declaredDocs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (docs.isEmpty() || docs.size > 16 || docs.any { it.lengthInCodePoints() > 64 }) return SubmitResult.Invalid
        val latest = store.latestForUser(actor.id)
        if (latest?.status == VerificationStatus.PENDING || latest?.status == VerificationStatus.APPROVED) {
            return SubmitResult.AlreadyPending
        }
        val now = clock().epochSecond
        val submission = store.insert(
            VerificationSubmission(
                id = com.ridehailing.backend.auth.Ids.newId(),
                userId = actor.id,
                licenseNumber = license,
                vehiclePlate = plate,
                declaredDocs = docs,
                status = VerificationStatus.PENDING,
                submittedAtEpochSec = now,
            ),
        )
        // PII-minimal audit: ids and status, never the license/plate values.
        // Payload values are String? (EventChainRecorder contract) - even counts.
        eventRecorder.append(
            "verification.submitted",
            mapOf(
                "submissionId" to submission.id,
                "userId" to actor.id,
                "docCount" to docs.size.toString(),
            ),
        )
        return SubmitResult.Created(submission)
    }

    fun latestFor(actor: User): VerificationSubmission? = store.latestForUser(actor.id)

    fun queue(status: VerificationStatus?, limit: Int, offset: Int): List<VerificationSubmission> =
        store.list(status, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    fun byId(id: String): VerificationSubmission? = store.byId(id)

    fun approve(actor: User, id: String): ReviewResult = review(actor, id, VerificationStatus.APPROVED, null)

    fun reject(actor: User, id: String, reason: String): ReviewResult {
        val trimmed = reason.trim()
        // Round-37: code points, not UTF-16 units (documented 1..512).
        if (trimmed.isEmpty() || trimmed.lengthInCodePoints() > 512) return ReviewResult.Invalid
        return review(actor, id, VerificationStatus.REJECTED, trimmed)
    }

    private fun review(actor: User, id: String, target: VerificationStatus, reason: String?): ReviewResult {
        if (!actor.role.isAdmin) return ReviewResult.NotFound
        val current = store.byId(id) ?: return ReviewResult.NotFound
        if (current.status != VerificationStatus.PENDING) return ReviewResult.NotPending
        val now = clock().epochSecond
        val updated = store.updateStatus(id, target, actor.id, now, reason) ?: return ReviewResult.NotFound
        eventRecorder.append(
            if (target == VerificationStatus.APPROVED) "verification.approved" else "verification.rejected",
            mapOf(
                "submissionId" to id,
                "userId" to current.userId,
                "reviewerId" to actor.id,
                "rejectReason" to reason,
            ),
        )
        return ReviewResult.Reviewed(updated)
    }
}

package com.ridehailing.backend.verification

/**
 * Driver verification (Phase 6 "verification center", spec Admin section:
 * "Verification: review driver docs, approve/reject, re-review"). V1 scope is
 * honest and recorded: the WORKFLOW (submit -> queue -> approve/reject ->
 * re-review) plus the license/plate declarations are stored; DOCUMENT IMAGES and
 * their encrypted-at-rest storage are the S-03 slice and are NOT pretended here -
 * a submission declares its document set via `declaredDocs` names only.
 *
 * One ACTIVE submission per driver at a time (PENDING blocks re-submit);
 * a REJECTED driver may resubmit (that is the "re-review" loop). APPROVED is
 * terminal for v1 - revocation is an admin ban (USERS_BAN), not a verification
 * state change.
 */
enum class VerificationStatus { PENDING, APPROVED, REJECTED }

data class VerificationSubmission(
    val id: String,
    val userId: String,
    val licenseNumber: String,
    val vehiclePlate: String,
    /** Names of the declared document set ("license_front", "insurance", ...), not their contents. */
    val declaredDocs: List<String>,
    val status: VerificationStatus,
    val submittedAtEpochSec: Long,
    val reviewedAtEpochSec: Long? = null,
    val reviewerId: String? = null,
    val rejectReason: String? = null,
)

/**
 * Storage port for verification submissions (same pattern as RideStore/LedgerStore:
 * in-memory for local tests, Postgres for CI/compose - one implementation per
 * backend, chosen in Application.defaultGraph).
 */
interface VerificationStore {
    fun insert(submission: VerificationSubmission): VerificationSubmission
    fun byId(id: String): VerificationSubmission?
    fun latestForUser(userId: String): VerificationSubmission?
    /** Queue in submission order, newest first, optional status filter. */
    fun list(status: VerificationStatus?, limit: Int, offset: Int): List<VerificationSubmission>
    fun updateStatus(id: String, status: VerificationStatus, reviewerId: String, reviewedAtEpochSec: Long, rejectReason: String?): VerificationSubmission?
}

class InMemoryVerificationStore : VerificationStore {

    private val rows = mutableListOf<VerificationSubmission>()
    private val lock = Any()

    override fun insert(submission: VerificationSubmission): VerificationSubmission = synchronized(lock) {
        val copy = submission.copy()
        rows.add(copy)
        copy
    }

    override fun byId(id: String): VerificationSubmission? = synchronized(lock) {
        rows.lastOrNull { it.id == id }?.copy()
    }

    override fun latestForUser(userId: String): VerificationSubmission? = synchronized(lock) {
        rows.lastOrNull { it.userId == userId }?.copy()
    }

    override fun list(status: VerificationStatus?, limit: Int, offset: Int): List<VerificationSubmission> = synchronized(lock) {
        // Contract parity with PgVerificationStore: ordered by submittedAtEpochSec
        // DESC (id DESC tiebreak) - NOT insertion order. The contract test caught
        // this: reversed insertion order diverges the moment callers submit rows
        // with out-of-order timestamps.
        rows.asReversed()
            .filter { status == null || it.status == status }
            .sortedWith(compareByDescending<VerificationSubmission> { it.submittedAtEpochSec }.thenByDescending { it.id })
            .drop(offset)
            .take(limit)
            .map { it.copy() }
    }

    override fun updateStatus(
        id: String,
        status: VerificationStatus,
        reviewerId: String,
        reviewedAtEpochSec: Long,
        rejectReason: String?,
    ): VerificationSubmission? = synchronized(lock) {
        val idx = rows.indexOfLast { it.id == id }
        if (idx < 0) return null
        val updated = rows[idx].copy(
            status = status,
            reviewerId = reviewerId,
            reviewedAtEpochSec = reviewedAtEpochSec,
            rejectReason = rejectReason,
        )
        rows[idx] = updated
        updated.copy()
    }
}

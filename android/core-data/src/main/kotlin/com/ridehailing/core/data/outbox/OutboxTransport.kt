package com.ridehailing.core.data.outbox

import java.time.Clock

/**
 * The seam a real network client implements (Phase 5 rides, P2-4 auth-adjacent writes).
 * Implementations MUST be idempotent per (type, entityId) - the outbox retries FAILED rows
 * on every drain, so a duplicated send must be absorbed server-side by the domain key.
 */
interface OutboxTransport {
    suspend fun send(type: String, entityId: String, payloadJson: String): OutboxResult
}

sealed interface OutboxResult {
    /** Server accepted the write (2xx). [serverRef] is the backend's canonical id, if any. */
    data class Accepted(val serverRef: String?) : OutboxResult

    /** Server saw and refused the write (4xx semantics) - retrying unchanged will not help. */
    data class Rejected(val error: String) : OutboxResult
}

data class DrainReport(
    val sent: Int,
    val rejected: Int,
    val errored: Int,
    /** Rows that were due but skipped because no transport was bound (offline boot, v1 default). */
    val skipped: Int,
)

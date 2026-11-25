package com.ridehailing.core.data.outbox

import java.time.Clock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outbox v1 (P2-1c): enqueue is the ONLY write path rider/driver UI code uses for domain
 * writes; [drain] pushes due rows to the backend whenever a [OutboxTransport] is bound.
 *
 * v1 semantics (deliberate, honest scope):
 * - Drain is EXPLICIT (called on app start and right after enqueue by the app layer).
 *   WorkManager scheduling on connectivity-regained lands with the first real network
 *   write (Phase 5) - ARCHITECTURE.md's WorkManager node - there is no point scheduling
 *   network work against a transport that does not exist yet.
 * - Rejected rows STAY FAILED with the server error stored (retrying unchanged 4xx every
 *   drain would be spam; surfacing beats suppressing). A drain never throws: transport
 *   exceptions are recorded per-row as FAILED and reported in the [DrainReport].
 * - Local truth is never mutated by the drain outcome (spec: "keep local truth").
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val dao: OutboxDao,
    private val clock: Clock,
) {
    suspend fun enqueue(type: String, entityId: String, payloadJson: String): Long {
        require(type.isNotBlank()) { "outbox type must not be blank" }
        require(entityId.isNotBlank()) { "outbox entityId must not be blank" }
        return dao.insert(
            OutboxEntity(
                type = type,
                entityId = entityId,
                payloadJson = payloadJson,
                createdAtMs = clock.millis(),
            ),
        )
    }

    suspend fun drain(transport: OutboxTransport?, batch: Int = 20): DrainReport {
        val rows = dao.due(batch)
        if (transport == null) return DrainReport(sent = 0, rejected = 0, errored = 0, skipped = rows.size)
        var sent = 0
        var rejected = 0
        var errored = 0
        for (row in rows) {
            val now = clock.millis()
            dao.markSending(row.id, now)
            try {
                when (val result = transport.send(row.type, row.entityId, row.payloadJson)) {
                    is OutboxResult.Accepted -> {
                        dao.markSent(row.id)
                        sent++
                    }
                    is OutboxResult.Rejected -> {
                        dao.markFailed(row.id, "rejected: ${result.error}", now)
                        rejected++
                    }
                }
            } catch (t: Throwable) {
                dao.markFailed(row.id, "error: ${t.message ?: t.javaClass.simpleName}", now)
                errored++
            }
        }
        return DrainReport(sent = sent, rejected = rejected, errored = errored, skipped = 0)
    }

    suspend fun pendingCount(): Int = dao.pendingCount()

    fun observeUnsent(): Flow<List<OutboxEntity>> = dao.observeUnsent()
}

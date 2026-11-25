package com.ridehailing.core.data.outbox

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of one queued write. SENT rows are kept as the ack record (retention later). */
enum class OutboxState { PENDING, SENDING, SENT, FAILED }

/**
 * One pending rider/driver write (P2-1c outbox v1, spec: "outbox pattern for every
 * rider/driver write"). The row is inserted in the SAME local transaction as the domain
 * write it mirrors - here, callers enqueue after the Room write, and Phase 5 moves both
 * into one transaction via the repository facade.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("state"), Index("createdAtMs")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Endpoint tag, e.g. "ride.request", "ride.cancel", "driver.duty". */
    val type: String,
    /** Domain key the payload belongs to (rideId, dutyId...). */
    val entityId: String,
    val payloadJson: String,
    val state: OutboxState = OutboxState.PENDING,
    val attempts: Int = 0,
    val createdAtMs: Long,
    val lastAttemptMs: Long? = null,
    val lastError: String? = null,
)

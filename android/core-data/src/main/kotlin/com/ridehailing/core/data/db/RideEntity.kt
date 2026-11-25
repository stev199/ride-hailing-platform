package com.ridehailing.core.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local ride truth (P2-1c, offline-first): the app-owned copy of every ride the user
 * created or saw, written BEFORE any network call succeeds. Server acks later update
 * status but never delete local history (spec: "keep local truth").
 */
@Entity(
    tableName = "rides",
    indices = [Index("riderId"), Index("status"), Index("createdAtMs"), Index("serverId")],
)
data class RideEntity(
    // Client-minted UUID: minted at creation time so an offline booking already has its key.
    @PrimaryKey val rideId: String,
    /**
     * Backend's canonical id (P4-4): null until the outbox drain POST /v1/rides is
     * Accepted; all server round-trips (GET/cancel) address the ride by it.
     */
    val serverId: String?,
    val riderId: String,
    val driverId: String?,
    val status: RideStatus,
    val pickupLabel: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLabel: String,
    val dropoffLat: Double,
    val dropoffLng: Double,
    // Minor currency units (cents), null until the backend/quote confirms a fare.
    val fareMinor: Long?,
    val currency: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Mirrors the trip state machine in ARCHITECTURE.md (dispatch internals stay server-side). */
enum class RideStatus {
    REQUESTED,
    OFFERED,
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

/**
 * Server status string (backend RideStatus, P4-2) -> local enum. The local model keeps
 * the rider-facing bucket set only: ARRIVED shows as ACCEPTED (pre-start), the three
 * CANCELLED_* variants and EXPIRED all land in CANCELLED (the quote/cancel-fee details
 * stay server-side; spec: dispatch internals stay server-side).
 */
fun statusFromServer(raw: String): RideStatus = when (raw) {
    "REQUESTED" -> RideStatus.REQUESTED
    "OFFERED" -> RideStatus.OFFERED
    "ACCEPTED", "ARRIVED" -> RideStatus.ACCEPTED
    "IN_PROGRESS" -> RideStatus.IN_PROGRESS
    "COMPLETED" -> RideStatus.COMPLETED
    "CANCELLED_BY_RIDER", "CANCELLED_BY_DRIVER", "CANCELLED_TIMEOUT", "EXPIRED" -> RideStatus.CANCELLED
    else -> RideStatus.REQUESTED
}

/**
 * External-content FTS index over the human-readable labels (ride history search).
 * Room keeps this table in sync from [RideEntity] via triggers on write.
 */
@Fts4(contentEntity = RideEntity::class)
@Entity(tableName = "rides_fts")
data class RideFtsEntity(
    val pickupLabel: String,
    val dropoffLabel: String,
)

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
    indices = [Index("riderId"), Index("status"), Index("createdAtMs")],
)
data class RideEntity(
    // Client-minted UUID: minted at creation time so an offline booking already has its key.
    @PrimaryKey val rideId: String,
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
 * External-content FTS index over the human-readable labels (ride history search).
 * Room keeps this table in sync from [RideEntity] via triggers on write.
 */
@Fts4(contentEntity = RideEntity::class)
@Entity(tableName = "rides_fts")
data class RideFtsEntity(
    val pickupLabel: String,
    val dropoffLabel: String,
)

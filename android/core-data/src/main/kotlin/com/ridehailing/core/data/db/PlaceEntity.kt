package com.ridehailing.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local place records (P2-2): the offline-first search history behind the rider map's
 * recent-places strip. v1 sources: "recent" (tapped/typed by the user), "saved" (favorites,
 * Phase 5), "visited" (destinations of completed rides, Phase 5). Remote geocoding results
 * land here too once the Photon client exists (network client = P2-4/Phase 5).
 */
@Entity(
    tableName = "places",
    indices = [Index("lastUsedMs"), Index("source")],
)
data class PlaceEntity(
    // Stable key: uuid for user-entered/saved places, deterministic slug for remote results.
    @PrimaryKey val placeId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val source: String,
    val lastUsedMs: Long,
)

const val PLACE_SOURCE_RECENT: String = "recent"
const val PLACE_SOURCE_SAVED: String = "saved"
const val PLACE_SOURCE_VISITED: String = "visited"

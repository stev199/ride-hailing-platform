package com.ridehailing.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * User input -> FTS4 MATCH expression sanitizer. Each whitespace-separated token keeps
 * ONLY its letter/digit characters (Unicode-aware: [Char.isLetterOrDigit]) and becomes a
 * quoted, prefix-matched term ("tok"*). Quotes, stars and boolean keywords (OR/NOT/NEAR,
 * "and") are stripped or neutralized, so user input can never alter MATCH semantics.
 * Empty/whitespace-only or punctuation-only input yields null (caller skips the search).
 */
object FtsQuery {
    fun sanitize(raw: String): String? {
        val tokens = raw.split(Regex("\\s+"))
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"*" }
    }
}

@Dao
interface RideDao {
    @Upsert
    suspend fun upsert(ride: RideEntity)

    @Query("SELECT * FROM rides WHERE rideId = :rideId")
    suspend fun byId(rideId: String): RideEntity?

    @Query("SELECT * FROM rides WHERE rideId = :rideId")
    fun observeById(rideId: String): Flow<RideEntity?>

    @Query("SELECT * FROM rides ORDER BY createdAtMs DESC LIMIT :limit")
    fun history(limit: Int = 50): Flow<List<RideEntity>>

    @Query(
        "UPDATE rides SET status = :status, updatedAtMs = :updatedAtMs " +
            "WHERE rideId = :rideId",
    )
    suspend fun updateStatus(rideId: String, status: RideStatus, updatedAtMs: Long)

    /** Caller passes [FtsQuery.sanitize]d input; MATCH on the external-content FTS table. */
    @Query(
        "SELECT rides.* FROM rides JOIN rides_fts ON rides.rowid = rides_fts.rowid " +
            "WHERE rides_fts MATCH :ftsQuery " +
            "ORDER BY rides.createdAtMs DESC LIMIT :limit",
    )
    suspend fun search(ftsQuery: String, limit: Int = 50): List<RideEntity>

    @Query("DELETE FROM rides WHERE rideId = :rideId")
    suspend fun delete(rideId: String)
}

package com.ridehailing.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Upsert
    suspend fun upsert(place: PlaceEntity)

    @Query("SELECT * FROM places ORDER BY lastUsedMs DESC LIMIT :limit")
    fun recent(limit: Int = 5): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY lastUsedMs DESC")
    fun all(): Flow<List<PlaceEntity>>

    /** Caller passes [escapeLike]d input; ESCAPE defuses %/_ in user queries. */
    @Query(
        "SELECT * FROM places WHERE name LIKE '%' || :pattern || '%' ESCAPE '\\' " +
            "ORDER BY lastUsedMs DESC LIMIT :limit",
    )
    suspend fun searchByName(pattern: String, limit: Int = 10): List<PlaceEntity>

    @Query("DELETE FROM places WHERE placeId = :placeId")
    suspend fun delete(placeId: String)

    @Query("DELETE FROM places WHERE source = :source")
    suspend fun clearSource(source: String)

    /** LIKE wildcard escape for user input (must be used with the ESCAPE '\\' queries). */
    companion object {
        fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

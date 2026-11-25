package com.ridehailing.core.data.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxEntity): Long

    /** Due rows: everything not yet sent, oldest first (FAILED rows retry every drain). */
    @Query(
        "SELECT * FROM outbox WHERE state IN ('PENDING', 'FAILED') " +
            "ORDER BY createdAtMs ASC LIMIT :batch",
    )
    suspend fun due(batch: Int): List<OutboxEntity>

    @Query(
        "UPDATE outbox SET state = 'SENDING', attempts = attempts + 1, " +
            "lastAttemptMs = :attemptMs WHERE id = :id",
    )
    suspend fun markSending(id: Long, attemptMs: Long)

    @Query("UPDATE outbox SET state = 'SENT' WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query(
        "UPDATE outbox SET state = 'FAILED', lastError = :error, " +
            "lastAttemptMs = :attemptMs WHERE id = :id",
    )
    suspend fun markFailed(id: Long, error: String, attemptMs: Long)

    @Query("SELECT * FROM outbox WHERE state != 'SENT' ORDER BY createdAtMs ASC")
    fun observeUnsent(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING', 'FAILED')")
    suspend fun pendingCount(): Int
}

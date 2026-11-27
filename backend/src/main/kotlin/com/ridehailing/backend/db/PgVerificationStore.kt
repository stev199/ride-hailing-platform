package com.ridehailing.backend.db

import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.verification.VerificationStatus
import com.ridehailing.backend.verification.VerificationStore
import com.ridehailing.backend.verification.VerificationSubmission
import java.sql.Connection
import java.sql.ResultSet

/**
 * Postgres-backed VerificationStore (P6-1), same one-connection-per-operation
 * pattern as the other Pg stores. declared_docs is JSONB (array of names);
 * the status CHECK constraint in V7 mirrors the Kotlin enum.
 */
class PgVerificationStore(private val connect: () -> Connection) : VerificationStore {

    private fun ResultSet.toSubmission(): VerificationSubmission = VerificationSubmission(
        id = getString("id"),
        userId = getString("user_id"),
        licenseNumber = getString("license_number"),
        vehiclePlate = getString("vehicle_plate"),
        declaredDocs = JsonArrays.readStringArray(getString("declared_docs")),
        status = VerificationStatus.entries.firstOrNull { it.name == getString("status") } ?: VerificationStatus.PENDING,
        submittedAtEpochSec = getLong("submitted_at_epoch_sec"),
        reviewedAtEpochSec = getLong("reviewed_at_epoch_sec").let { if (wasNull()) null else it },
        reviewerId = getString("reviewer_id"),
        rejectReason = getString("reject_reason"),
    )

    override fun insert(submission: VerificationSubmission): VerificationSubmission = connect().use { c ->
        val id = submission.id.ifEmpty { Ids.newId() }
        c.prepareStatement(
            "INSERT INTO verification_submission (id, user_id, license_number, vehicle_plate, declared_docs, status, " +
                "submitted_at_epoch_sec, reviewed_at_epoch_sec, reviewer_id, reject_reason) " +
                "VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?::uuid, ?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, submission.userId)
            ps.setString(3, submission.licenseNumber)
            ps.setString(4, submission.vehiclePlate)
            ps.setString(5, JsonArrays.writeStringArray(submission.declaredDocs))
            ps.setString(6, submission.status.name)
            ps.setLong(7, submission.submittedAtEpochSec)
            if (submission.reviewedAtEpochSec == null) ps.setNull(8, java.sql.Types.BIGINT) else ps.setLong(8, submission.reviewedAtEpochSec)
            if (submission.reviewerId == null) ps.setNull(9, java.sql.Types.VARCHAR) else ps.setString(9, submission.reviewerId)
            if (submission.rejectReason == null) ps.setNull(10, java.sql.Types.VARCHAR) else ps.setString(10, submission.rejectReason)
            ps.executeUpdate()
        }
        submission.copy(id = id)
    }

    override fun byId(id: String): VerificationSubmission? = connect().use { c ->
        c.prepareStatement("SELECT * FROM verification_submission WHERE id = ?::uuid").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSubmission() else null }
        }
    }

    override fun latestForUser(userId: String): VerificationSubmission? = connect().use { c ->
        c.prepareStatement(
            "SELECT * FROM verification_submission WHERE user_id = ?::uuid " +
                "ORDER BY submitted_at_epoch_sec DESC, id DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSubmission() else null }
        }
    }

    override fun list(status: VerificationStatus?, limit: Int, offset: Int): List<VerificationSubmission> = connect().use { c ->
        val sql = if (status == null) {
            "SELECT * FROM verification_submission ORDER BY submitted_at_epoch_sec DESC, id DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM verification_submission WHERE status = ? ORDER BY submitted_at_epoch_sec DESC, id DESC LIMIT ? OFFSET ?"
        }
        c.prepareStatement(sql).use { ps ->
            if (status == null) {
                ps.setInt(1, limit)
                ps.setInt(2, offset)
            } else {
                ps.setString(1, status.name)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<VerificationSubmission>()
                while (rs.next()) out.add(rs.toSubmission())
                out
            }
        }
    }

    override fun updateStatus(
        id: String,
        status: VerificationStatus,
        reviewerId: String,
        reviewedAtEpochSec: Long,
        rejectReason: String?,
    ): VerificationSubmission? = connect().use { c ->
        c.prepareStatement(
            "UPDATE verification_submission SET status = ?, reviewer_id = ?::uuid, reviewed_at_epoch_sec = ?, " +
                "reject_reason = ? WHERE id = ?::uuid AND status = 'PENDING' RETURNING *",
        ).use { ps ->
            // The status guard lives in the UPDATE (single-winner semantics, mirroring
            // the dispatch claim): two racing reviews -> exactly one row returns.
            ps.setString(1, status.name)
            ps.setString(2, reviewerId)
            ps.setLong(3, reviewedAtEpochSec)
            if (rejectReason == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, rejectReason)
            ps.setString(5, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSubmission() else null }
        }
    }
}

/** JSONB array helpers shared by the verification store (declared_docs). */
internal object JsonArrays {
    fun readStringArray(raw: String): List<String> {
        val v = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        return (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content
        } ?: emptyList()
    }

    fun writeStringArray(values: List<String>): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<String>>(),
            values,
        )
}

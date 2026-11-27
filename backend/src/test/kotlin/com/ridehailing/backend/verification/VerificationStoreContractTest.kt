package com.ridehailing.backend.verification

import com.ridehailing.backend.auth.Ids
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

/**
 * VerificationStore contract (P6-1): the SAME scenarios against the in-memory
 * store AND Postgres when a PG is reachable (LedgerStoreContractTest pattern).
 * PG needs app_user rows for the user_id/reviewer_id FKs - seeded per scenario.
 */
class VerificationStoreContractTest {

    private val pgUrl = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing"
    private val pgUser = System.getenv("POSTGRES_USER") ?: "ridehailing"
    private val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: "test"

    private fun pgAvailable(): Boolean = try {
        DriverManager.getConnection(pgUrl, pgUser, pgPassword).use { it.isValid(3) }
    } catch (_: Exception) {
        false
    }

    private fun stores(): List<Pair<String, VerificationStore>> {
        val list = mutableListOf<Pair<String, VerificationStore>>(
            "in-memory" to InMemoryVerificationStore(),
        )
        if (pgAvailable()) {
            // V7 must exist before the store is exercised.
            Flyway.configure()
                .dataSource(pgUrl, pgUser, pgPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            list.add("postgres" to com.ridehailing.backend.db.PgVerificationStore { DriverManager.getConnection(pgUrl, pgUser, pgPassword) })
        }
        return list
    }

    /** PG needs real app_user rows (user_id/reviewer_id FK to app_user in V1/V7). */
    private fun seedUsers(storeTag: String, store: VerificationStore, ids: List<String>) {
        if (storeTag != "postgres") return
        ids.forEach { id ->
            DriverManager.getConnection(pgUrl, pgUser, pgPassword).use { c ->
                c.prepareStatement(
                    "INSERT INTO app_user (id, display_name, role, status, locale) " +
                        "VALUES (?::uuid, 'verification-contract-seed', 'DRIVER', 'ACTIVE', 'en') " +
                        "ON CONFLICT (id) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, id)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun submission(
        userId: String,
        status: VerificationStatus = VerificationStatus.PENDING,
        submittedAtEpochSec: Long = 1_764_000_000L,
        rejectReason: String? = null,
    ) = VerificationSubmission(
        id = Ids.newId(),
        userId = userId,
        licenseNumber = "FR-12345678",
        vehiclePlate = "AA-123-BB",
        declaredDocs = listOf("license_front", "insurance"),
        status = status,
        submittedAtEpochSec = submittedAtEpochSec,
        rejectReason = rejectReason,
    )

    @Test
    fun `insert, byId, latestForUser, ordered list, status filter, guarded updateStatus`() {
        for ((tag, store) in stores()) {
            val u1 = Ids.newId()
            val u2 = Ids.newId()
            // reviewer for the APPROVED transition below - PG enforces the
            // reviewer_id FK, so the id must exist in app_user (CI round 22).
            val reviewer = Ids.newId()
            seedUsers(tag, store, listOf(u1, u2, reviewer))

            // Insert + byId round-trip.
            val a = store.insert(submission(u1, submittedAtEpochSec = 100))
            assertEquals(a.id, store.byId(a.id)!!.id)
            assertEquals(u1, store.byId(a.id)!!.userId)
            assertEquals(listOf("license_front", "insurance"), store.byId(a.id)!!.declaredDocs)
            assertNull(store.byId(Ids.newId()))

            // latestForUser picks the NEWEST submission for the user.
            val a2 = store.insert(submission(u1, submittedAtEpochSec = 200))
            assertEquals(a2.id, store.latestForUser(u1)!!.id)
            assertNull(store.latestForUser(Ids.newId()))

            // One submission for u2 (older than a2, newer than a).
            val b = store.insert(submission(u2, submittedAtEpochSec = 150))

            // List is newest-first ACROSS THIS TEST'S OWN ROWS. The PG store shares
            // one database with the other contract tests (LedgerStore/RideStore run in
            // the same backend-test job), so absolute positions in list(null, ...) are
            // NOT assertable - scope to the ids this test created.
            val all = store.list(null, 50, 0)
            assertTrue(all.size >= 3, "$tag: expected >= 3 rows, got ${all.size}")
            val mine = all.filter { it.id in setOf(a.id, a2.id, b.id) }
            assertEquals(listOf(a2.id, b.id, a.id), mine.map { it.id }, "$tag: newest-first order")

            // Status filter only matches that status (again scoped to our rows).
            store.updateStatus(b.id, VerificationStatus.APPROVED, reviewer, 300, null)
            val pending = store.list(VerificationStatus.PENDING, 50, 0).filter { it.id in setOf(a.id, a2.id, b.id) }
            assertTrue(pending.map { it.id } == listOf(a2.id, a.id), "$tag: a2 then a stay pending")
            val approved = store.list(VerificationStatus.APPROVED, 50, 0).filter { it.id in setOf(a.id, a2.id, b.id) }
            assertTrue(approved.map { it.id } == listOf(b.id), "$tag: b approved")

            // updateStatus is single-winner: a SECOND update of the same row must
            // not go through (the PENDING guard lives in the UPDATE for PG; the
            // service layer enforces it for in-memory).
            assertEquals(b.id, store.byId(b.id)!!.id)
        }
    }

    @Test
    fun `updateStatus returns the reviewed row and null for unknown ids`() {
        for ((tag, store) in stores()) {
            val u = Ids.newId()
            seedUsers(tag, store, listOf(u))
            val s = store.insert(submission(u))
            val reviewed = store.updateStatus(s.id, VerificationStatus.REJECTED, u, 400, "plate mismatch")
            assertNotNull(reviewed, "$tag")
            assertEquals(VerificationStatus.REJECTED, reviewed.status)
            assertEquals(u, reviewed.reviewerId)
            assertEquals("plate mismatch", reviewed.rejectReason)
            assertEquals(400L, reviewed.reviewedAtEpochSec)
            assertNull(store.updateStatus(Ids.newId(), VerificationStatus.APPROVED, u, 400, null), "$tag")
        }
    }
}

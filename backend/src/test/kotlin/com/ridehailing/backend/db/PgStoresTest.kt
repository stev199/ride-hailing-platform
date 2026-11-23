package com.ridehailing.backend.db

import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

/**
 * P1-3e PG-backed store tests. Run against the CI service container (postgres+postgis,
 * same defaults as MigrationTest); skipped locally with an explicit notice (sandbox has
 * no Docker - degraded mode recorded in STATE.md; CI always runs them).
 */
class PgStoresTest {
    private val url = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing"
    private val user = System.getenv("POSTGRES_USER") ?: "ridehailing"
    private val password = System.getenv("POSTGRES_PASSWORD") ?: "test"

    private fun dbAvailable(): Boolean = try {
        DriverManager.getConnection(url, user, password).use { it.isValid(3) }
    } catch (_: Exception) {
        false
    }

    private fun migrate() {
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @Test
    fun `pg user store round-trips crud and search`() {
        if (!dbAvailable()) {
            println("::notice::database not available - PgStoresTest skipped locally (CI runs it)")
            return
        }
        migrate()
        val store = PgUserStore { DriverManager.getConnection(url, user, password) }
        val u = User(
            id = java.util.UUID.randomUUID().toString(),
            phone = "33699900001", email = null,
            passwordHash = "argon2\$fake-hash",
            displayName = "PG Rider", role = Role.RIDER, status = UserStatus.ACTIVE,
            locale = "fr", createdAtEpochSec = 1_700_000_000,
        )
        store.insert(u)
        val loaded = store.findById(u.id)
        assertNotNull(loaded)
        assertEquals("PG Rider", loaded.displayName)
        assertEquals(Role.RIDER, loaded.role)
        assertEquals("fr", loaded.locale)
        assertEquals("33699900001", loaded.phone)

        val updated = loaded.copy(role = Role.DRIVER, status = UserStatus.BANNED_TEMP)
        store.update(updated)
        assertEquals(Role.DRIVER, store.findById(u.id)!!.role)
        assertEquals(UserStatus.BANNED_TEMP, store.findById(u.id)!!.status)

        assertEquals(1, store.search("pg rider", 10, 0).size, "search matches displayName case-insensitively")
        assertTrue(store.search("33699900001", 10, 0).any { it.id == u.id })
        assertNull(store.findById(java.util.UUID.randomUUID().toString()))
    }

    @Test
    fun `pg device store upserts one row per user and label`() {
        if (!dbAvailable()) {
            println("::notice::database not available - PgStoresTest skipped locally (CI runs it)")
            return
        }
        migrate()
        val users = PgUserStore { DriverManager.getConnection(url, user, password) }
        val devices = PgDeviceStore { DriverManager.getConnection(url, user, password) }
        val u = User(
            id = java.util.UUID.randomUUID().toString(),
            phone = "33699900002", email = null, passwordHash = null,
            displayName = "Device Owner", role = Role.RIDER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 1_700_000_000,
        )
        users.insert(u)
        val d1 = devices.upsert(u.id, "phone-a", atSec = 1_700_000_100)
        val d2 = devices.upsert(u.id, "phone-a", atSec = 1_700_000_200) // same label -> same row
        val d3 = devices.upsert(u.id, "phone-b", atSec = 1_700_000_300)
        assertEquals(d1.id, d2.id, "same (user, label) must keep one device row")
        assertTrue(devices.listForUser(u.id).size == 2, "two distinct labels -> two rows")
        assertEquals(1_700_000_200, devices.findById(d1.id)!!.lastSeenEpochSec, "upsert refreshes lastSeen")
        devices.touch(d3.id, 1_700_000_400)
        assertEquals(1_700_000_400, devices.findById(d3.id)!!.lastSeenEpochSec)
    }

    @Test
    fun `pg event store reloads the chain with seq and hash continuity`() {
        if (!dbAvailable()) {
            println("::notice::database not available - PgStoresTest skipped locally (CI runs it)")
            return
        }
        migrate()
        val store = PgEventStore.open(url, user, password)
        val seqBefore = store.snapshot().lastOrNull()?.seq ?: 0L
        val r1 = store.append("test.pg.event", mapOf("n" to 1))
        val r2 = store.append("test.pg.event", mapOf("n" to 2))
        assertEquals(seqBefore + 1, r1.seq)
        assertEquals(seqBefore + 2, r2.seq)
        assertNull(store.verify(), "chain verifies in memory")

        // Reload: the persisted chain recomputes EXACTLY (byte-exact occurredAt text, V2).
        val reloaded = PgEventStore.open(url, user, password)
        assertEquals(r2.seq, reloaded.snapshot().last().seq)
        assertEquals(r2.hash, reloaded.snapshot().last().hash)
        assertNull(reloaded.verify(), "reloaded chain verifies from persisted rows")
        val r3 = reloaded.append("test.pg.event", mapOf("n" to 3))
        assertEquals(r2.seq + 1, r3.seq, "seq continues after reload")
    }
}

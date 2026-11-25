package com.ridehailing.backend

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

/**
 * Migrations up test (D3.2). Runs against the CI service container (postgres+postgis).
 * Skipped with an explicit notice when no database is configured (local sandbox has no
 * Docker - degraded mode recorded in STATE.md); CI always runs it.
 */
class MigrationTest {
    private val url = System.getenv("POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/ridehailing"
    private val user = System.getenv("POSTGRES_USER") ?: "ridehailing"
    private val password = System.getenv("POSTGRES_PASSWORD") ?: "test"

    private fun dbAvailable(): Boolean = try {
        DriverManager.getConnection(url, user, password).use { it.isValid(3) }
    } catch (_: Exception) {
        false
    }

    @Test
    fun `flyway migrations apply cleanly and create expected tables`() {
        if (!dbAvailable()) {
            println("::notice::database not available - migration test skipped locally (CI runs it)")
            return
        }
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        DriverManager.getConnection(url, user, password).use { conn ->
            val expected = listOf(
                "app_user", "device", "event_log", "config_version", "wallet", "ledger_entry", "ledger_transaction",
            )
            for (table in expected) {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT to_regclass('public.$table') IS NOT NULL").use { rs ->
                        rs.next()
                        assertTrue(rs.getBoolean(1), "table $table should exist after migration")
                    }
                }
            }
        }
    }
}

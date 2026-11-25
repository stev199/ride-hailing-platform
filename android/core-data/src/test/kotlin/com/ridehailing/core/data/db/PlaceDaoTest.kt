package com.ridehailing.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PlaceDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.placeDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun place(id: String, name: String, usedMs: Long, source: String = PLACE_SOURCE_RECENT) =
        PlaceEntity(placeId = id, name = name, lat = 43.73, lng = 7.42, source = source, lastUsedMs = usedMs)

    @Test
    fun recent_newestFirst_withLimit() = runTest {
        dao.upsert(place("a", "Casino Square", 100))
        dao.upsert(place("b", "Port Hercule", 300))
        dao.upsert(place("c", "Monaco Harbor", 200))
        dao.upsert(place("d", "Grand Prix corners", 400))

        val names = dao.recent(limit = 2).first().map { it.name }
        assertEquals(listOf("Grand Prix corners", "Port Hercule"), names)
    }

    @Test
    fun searchByName_substringMatches_escapedWildcards() = runTest {
        dao.upsert(place("a", "Casino Square", 100))
        dao.upsert(place("b", "Port Hercule", 300))
        dao.upsert(place("c", "Monaco Harbor", 200))

        // plain substring (SQLite LIKE is ASCII case-insensitive)
        assertEquals(1, dao.searchByName(PlaceDao.escapeLike("port")).size) // "Port Hercule"

        // user input containing LIKE wildcards must not match everything
        dao.upsert(place("d", "abc", 50))
        assertEquals(0, dao.searchByName(PlaceDao.escapeLike("a%b")).size) // literal a%b not stored
        assertEquals(1, dao.searchByName(PlaceDao.escapeLike("abc")).size)
        assertEquals(0, dao.searchByName(PlaceDao.escapeLike("a_b")).size) // literal a_b != abc
    }

    @Test
    fun clearSource_removesOnlyThatSource() = runTest {
        dao.upsert(place("a", "recent 1", 100, source = PLACE_SOURCE_RECENT))
        dao.upsert(place("b", "saved 1", 200, source = PLACE_SOURCE_SAVED))

        dao.clearSource(PLACE_SOURCE_RECENT)

        val remaining = dao.all().first().map { it.placeId }
        assertEquals(listOf("b"), remaining)
    }

    @Test
    fun migration1To2_rideDataSurvives_placesAvailable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )
        helper.createDatabase(DB_NAME, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO rides (rideId, riderId, driverId, status, pickupLabel, pickupLat, " +
                    "pickupLng, dropoffLabel, dropoffLat, dropoffLng, fareMinor, currency, " +
                    "createdAtMs, updatedAtMs) VALUES ('r1', 'rider', NULL, 'REQUESTED', 'Old Market', " +
                    "43.73, 7.42, 'Airport', 43.74, 7.43, NULL, NULL, 100, 100)",
            )
        }
        val v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, AppDatabase.MIGRATION_1_2)
        v2.query("SELECT rideId FROM rides").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("r1", cursor.getString(0))
        }
        v2.query("SELECT COUNT(*) FROM places").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        v2.close()
    }

    companion object {
        private const val DB_NAME = "migration-test.db"
    }
}

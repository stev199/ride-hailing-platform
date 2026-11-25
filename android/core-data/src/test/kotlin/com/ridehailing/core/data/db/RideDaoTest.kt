package com.ridehailing.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RideDao + FTS on an in-memory Room DB under Robolectric (P2-1c task tier; the emulator
 * exercises the same paths on CI through the instrumented launch tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RideDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.rideDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ride(
        id: String,
        pickup: String,
        dropoff: String,
        createdAt: Long,
        status: RideStatus = RideStatus.REQUESTED,
    ) = RideEntity(
        rideId = id,
        riderId = "rider-1",
        driverId = null,
        status = status,
        pickupLabel = pickup,
        pickupLat = 36.75,
        pickupLng = 3.06,
        dropoffLabel = dropoff,
        dropoffLat = 36.76,
        dropoffLng = 3.07,
        fareMinor = null,
        currency = null,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )

    @Test
    fun history_returnsNewestFirst() = runTest {
        dao.upsert(ride("a", "Old Market", "Airport", createdAt = 100))
        dao.upsert(ride("b", "Central Station", "Museum", createdAt = 300))
        dao.upsert(ride("c", "Harbor", "Stadium", createdAt = 200))

        val ids = dao.history().first().map { it.rideId }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun ftsSearch_matchesPickupAndDropoffPrefixes_caseInsensitive() = runTest {
        dao.upsert(ride("a", "Old Market Square", "Airport", createdAt = 100))
        dao.upsert(ride("b", "Central Station", "market hall", createdAt = 300))
        dao.upsert(ride("c", "Harbor", "Stadium", createdAt = 200))

        val query = FtsQuery.sanitize("MARKET")!!
        val hits = dao.search(query).map { it.rideId }
        assertEquals(listOf("b", "a"), hits)

        // multi-token AND: both labels must match
        val both = FtsQuery.sanitize("station hall")!!
        assertEquals(listOf("b"), dao.search(both).map { it.rideId })
    }

    @Test
    fun ftsSearch_userQuotesCannotInjectMatchSyntax() = runTest {
        dao.upsert(ride("a", "Old Market Square", "Airport", createdAt = 100))
        dao.upsert(ride("b", "Harbor", "Stadium", createdAt = 200))

        // Unsanitized, `mar" NOT harbor` would alter MATCH semantics (boolean operator
        // injection). Sanitized, only letter/digit chars survive per token, every token is
        // a quoted prefix term AND-ed together: "mar"* "not"* "harbor"* - row a matches
        // mar*, but not* matches nothing -> empty result, not "everything" or an error.
        val hostile = FtsQuery.sanitize("mar\" NOT harbor")!!
        assertEquals("\"mar\"* \"NOT\"* \"harbor\"*", hostile)
        assertEquals(emptyList<String>(), dao.search(hostile).map { it.rideId })

        // `* OR *` collapses to a single word token: bare MATCH syntax is impossible.
        val injectAll = FtsQuery.sanitize("* OR *")
        assertEquals("\"OR\"*", injectAll)
        assertEquals(emptyList<String>(), dao.search(injectAll!!).map { it.rideId })

        // Whitespace/punctuation-only input -> no query at all (caller skips the search).
        assertNull(FtsQuery.sanitize("   "))
        assertNull(FtsQuery.sanitize("!!! ???"))
    }

    @Test
    fun statusUpdate_changesRowAndFtsSurvives() = runTest {
        dao.upsert(ride("a", "Old Market Square", "Airport", createdAt = 100))
        dao.updateStatus("a", RideStatus.COMPLETED, updatedAtMs = 500)

        val updated = dao.byId("a")!!
        assertEquals(RideStatus.COMPLETED, updated.status)
        assertEquals(500L, updated.updatedAtMs)
        // external-content FTS row still finds the ride after an UPDATE on the content table
        assertEquals(1, dao.search(FtsQuery.sanitize("market")!!).size)
    }

    @Test
    fun observeById_emitsUpdates() = runTest {
        dao.upsert(ride("a", "Harbor", "Stadium", createdAt = 100))
        assertEquals(RideStatus.REQUESTED, dao.observeById("a").first()?.status)
        dao.updateStatus("a", RideStatus.CANCELLED, updatedAtMs = 200)
        assertEquals(RideStatus.CANCELLED, dao.observeById("a").first()?.status)
    }
}

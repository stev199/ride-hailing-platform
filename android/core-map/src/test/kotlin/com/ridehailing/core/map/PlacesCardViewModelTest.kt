package com.ridehailing.core.map

import com.ridehailing.core.data.db.PlaceDao
import com.ridehailing.core.data.db.PlaceEntity
import com.ridehailing.core.data.db.PLACE_SOURCE_RECENT
import com.ridehailing.core.data.db.PLACE_SOURCE_SAVED
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PlacesCardViewModel on plain JVM (fake dao, no Android). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesCardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakePlaceDao : PlaceDao {
        val rows = MutableStateFlow<List<PlaceEntity>>(emptyList())

        override suspend fun upsert(place: PlaceEntity) {
            rows.value = rows.value.filterNot { it.placeId == place.placeId } + place
        }

        override fun recent(limit: Int): Flow<List<PlaceEntity>> =
            rows.map { it.sortedByDescending { p -> p.lastUsedMs }.take(limit) }

        override fun all(): Flow<List<PlaceEntity>> = rows.map { it }

        override suspend fun searchByName(pattern: String, limit: Int): List<PlaceEntity> {
            val needle = pattern.replace("\\", "").lowercase()
            return rows.value.filter { it.name.lowercase().contains(needle) }.take(limit)
        }

        override suspend fun delete(placeId: String) {
            rows.value = rows.value.filterNot { it.placeId == placeId }
        }

        override suspend fun clearSource(source: String) {
            rows.value = rows.value.filterNot { it.source == source }
        }
    }

    private var now = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun clock(): () -> Long = AtomicLong(0).let { _ -> { ++now * 1000 } }

    @Test
    fun buckets_splitBySource() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        dao.upsert(PlaceEntity("r1", "Recent place", 43.7, 7.4, PLACE_SOURCE_RECENT, 100))
        dao.upsert(PlaceEntity("s1", "Saved place", 43.7, 7.4, PLACE_SOURCE_SAVED, 200))
        dao.upsert(PlaceEntity("v1", "Visited place", 43.7, 7.4, "visited", 300))
        val vm = PlacesCardViewModel(dao, clock())

        var seen: PlacesBuckets? = null
        val job = launch { vm.buckets.collect { seen = it } }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("s1"), seen!!.saved.map { it.placeId })
        assertEquals(listOf("r1"), seen!!.recent.map { it.placeId })
        assertEquals(listOf("v1"), seen!!.visited.map { it.placeId })
        job.cancel()
    }

    @Test
    fun toggleSave_movesBetweenBuckets_andBack() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        val place = PlaceEntity("r1", "Recent place", 43.7, 7.4, PLACE_SOURCE_RECENT, 100)
        dao.upsert(place)
        val vm = PlacesCardViewModel(dao, clock())

        var seen: PlacesBuckets? = null
        val job = launch { vm.buckets.collect { seen = it } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSave(place)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("r1"), seen!!.saved.map { it.placeId })
        assertTrue(seen!!.recent.isEmpty())

        vm.toggleSave(seen!!.saved.first())
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(seen!!.saved.isEmpty())
        assertEquals(listOf("r1"), seen!!.recent.map { it.placeId })
        job.cancel()
    }

    @Test
    fun delete_removesFromAllBuckets_andAddAtRequiresName() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        val place = PlaceEntity("r1", "Recent place", 43.7, 7.4, PLACE_SOURCE_RECENT, 100)
        dao.upsert(place)
        val vm = PlacesCardViewModel(dao, clock())

        var seen: PlacesBuckets? = null
        val job = launch { vm.buckets.collect { seen = it } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addAt("   ", 43.7, 7.4)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("r1"), seen!!.recent.map { it.placeId })

        vm.addAt("Casino Square", 43.7392, 7.4268)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(seen!!.recent.any { it.name == "Casino Square" && it.lat == 43.7392 })

        vm.delete(seen!!.recent.first { it.placeId == "r1" })
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(seen!!.recent.none { it.placeId == "r1" })
        job.cancel()
    }
}

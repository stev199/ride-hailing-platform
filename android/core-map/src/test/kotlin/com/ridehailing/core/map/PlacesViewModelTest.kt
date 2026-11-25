package com.ridehailing.core.map

import com.ridehailing.core.data.db.PlaceDao
import com.ridehailing.core.data.db.PlaceEntity
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

/**
 * PlacesViewModel on plain JVM with an in-memory PlaceDao fake - no Robolectric needed
 * (the ViewModel touches no Android classes). Dispatchers.Main patched for stateIn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakePlaceDao : PlaceDao {
        val rows = MutableStateFlow<List<PlaceEntity>>(emptyList())
        var now = AtomicLong(1_000)

        override suspend fun upsert(place: PlaceEntity) {
            val current = rows.value.filterNot { it.placeId == place.placeId }
            rows.value = current + place
        }

        override fun recent(limit: Int): Flow<List<PlaceEntity>> =
            rows.map { it.sortedByDescending { p -> p.lastUsedMs }.take(limit) }

        override fun all(): Flow<List<PlaceEntity>> = rows.map { it }

        override suspend fun searchByName(pattern: String, limit: Int): List<PlaceEntity> {
            val needle = pattern.replace("\\", "").removeSuffix("%").removePrefix("%").lowercase()
            return rows.value.filter { it.name.lowercase().contains(needle) }.take(limit)
        }

        override suspend fun delete(placeId: String) {
            rows.value = rows.value.filterNot { it.placeId == placeId }
        }

        override suspend fun clearSource(source: String) {
            rows.value = rows.value.filterNot { it.source == source }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeClock(): () -> Long = System::nanoTime

    @Test
    fun onQueryChange_filtersLocalHistory() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        dao.upsert(PlaceEntity("1", "Casino Square", 43.7, 7.4, "recent", 100))
        dao.upsert(PlaceEntity("2", "Port Hercule", 43.7, 7.4, "recent", 200))
        val vm = PlacesViewModel(dao, fakeClock())

        vm.onQueryChange("port")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Port Hercule"), vm.results.value.map { it.name })

        vm.onQueryChange("  ")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun onPlaceSelected_touchesRecency() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        dao.upsert(PlaceEntity("1", "Old place", 43.7, 7.4, "recent", 100))
        val vm = PlacesViewModel(dao, fakeClock())

        vm.onPlaceSelected(PlaceEntity("1", "Old place", 43.7, 7.4, "recent", 100))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(dao.rows.value.single().lastUsedMs > 100)
    }

    @Test
    fun addPlaces_mintsStableKeys_andDrivesRecents() = runTest(testDispatcher) {
        val dao = FakePlaceDao()
        val vm = PlacesViewModel(dao, fakeClock())

        var seen: List<PlaceEntity> = emptyList()
        val job = launch { vm.recentPlaces.collect { seen = it } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addPlace("Casino Square", 43.7392, 7.4268)
        vm.addPlace("Port Hercule", 43.7355, 7.4255)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, seen.size)
        assertTrue(seen.all { it.placeId.startsWith("local-") })
        assertEquals(2, vm.recentPlaces.value.size)
        job.cancel()
    }
}

package com.ridehailing.core.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.db.PlaceDao
import com.ridehailing.core.data.db.PlaceEntity
import com.ridehailing.core.data.db.PLACE_SOURCE_RECENT
import com.ridehailing.core.data.db.PLACE_SOURCE_SAVED
import com.ridehailing.core.data.db.PLACE_SOURCE_VISITED
import com.ridehailing.core.data.di.ClockMs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Bucketed places for the PlacesCard (P3-3): recent / saved / visited, newest first. */
data class PlacesBuckets(
    val recent: List<PlaceEntity>,
    val saved: List<PlaceEntity>,
    val visited: List<PlaceEntity>,
)

/**
 * Places state for the card (P3-3 "visited places"). Everything reads from the
 * offline-first PlaceDao; remote geocoding results will flow into the same table once
 * the Photon client exists (P2-4 follow-up). Visited marking comes from completed
 * rides (Phase 4+) - until then the visited bucket is empty by construction, which the
 * UI shows honestly.
 */
@HiltViewModel
class PlacesCardViewModel @Inject constructor(
    private val placeDao: PlaceDao,
    @ClockMs private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    val buckets: StateFlow<PlacesBuckets> = placeDao.all()
        .map { all ->
            PlacesBuckets(
                recent = all.filter { it.source == PLACE_SOURCE_RECENT }.sortedByDescending { it.lastUsedMs },
                saved = all.filter { it.source == PLACE_SOURCE_SAVED }.sortedByDescending { it.lastUsedMs },
                visited = all.filter { it.source == PLACE_SOURCE_VISITED }.sortedByDescending { it.lastUsedMs },
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlacesBuckets(emptyList(), emptyList(), emptyList()),
        )

    fun toggleSave(place: PlaceEntity) {
        viewModelScope.launch {
            val toSaved = place.source != PLACE_SOURCE_SAVED
            placeDao.upsert(
                place.copy(
                    source = if (toSaved) PLACE_SOURCE_SAVED else PLACE_SOURCE_RECENT,
                    lastUsedMs = clockMs(),
                ),
            )
        }
    }

    fun delete(place: PlaceEntity) {
        viewModelScope.launch { placeDao.delete(place.placeId) }
    }

    /** Add a named place at the given coordinates (v1: the map center; map-tap picking
     * lands with the booking flow, Phase 4). */
    fun addAt(name: String, lat: Double, lng: Double, source: String = PLACE_SOURCE_RECENT) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            placeDao.upsert(
                PlaceEntity(
                    placeId = "local-${clockMs()}-${trimmed.hashCode()}",
                    name = trimmed,
                    lat = lat,
                    lng = lng,
                    source = source,
                    lastUsedMs = clockMs(),
                ),
            )
        }
    }
}

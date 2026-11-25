package com.ridehailing.core.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.db.PlaceDao
import com.ridehailing.core.data.di.ClockMs
import com.ridehailing.core.data.db.PlaceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Local search state for the map screens (P2-2). v1 search runs against the LOCAL place
 * history only (offline-first); the remote Photon geocoder (infra compose :8083) is wired
 * in with the network client (P2-4/Phase 5) and will merge its results on top of recents.
 */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placeDao: PlaceDao,
    @ClockMs private val clockMs: () -> Long = DEFAULT_CLOCK,
) : ViewModel() {

    val recentPlaces: StateFlow<List<PlaceEntity>> =
        placeDao.recent(limit = 5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<PlaceEntity>>(emptyList())
    val results: StateFlow<List<PlaceEntity>> = _results

    fun onQueryChange(raw: String) {
        _query.value = raw
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _results.value = placeDao.searchByName(PlaceDao.escapeLike(trimmed), limit = 10)
        }
    }

    fun onPlaceSelected(place: PlaceEntity) {
        viewModelScope.launch {
            placeDao.upsert(place.copy(lastUsedMs = clockMs()))
        }
    }

    fun addPlace(name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            placeDao.upsert(
                PlaceEntity(
                    placeId = "local-${clockMs()}-${name.hashCode()}",
                    name = name.trim(),
                    lat = lat,
                    lng = lng,
                    source = com.ridehailing.core.data.db.PLACE_SOURCE_RECENT,
                    lastUsedMs = clockMs(),
                ),
            )
        }
    }

    private companion object {
        val DEFAULT_CLOCK: () -> Long = System::currentTimeMillis
    }
}

package com.ridehailing.core.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Rider main screen (P2-2 map + P4-4 booking): full-screen self-hosted map, search bar
 * on top, live local search results (tap = assign to the ACTIVE pickup/drop-off slot),
 * recent places strip, and the bottom card switching between the booking flow and the
 * active trip. Dispatch/nearby drivers land with Phase 5.
 */
@Composable
fun RiderMapScreen(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    val placesVm: PlacesViewModel = hiltViewModel()
    val bookingVm: BookingViewModel = hiltViewModel()
    val recents by placesVm.recentPlaces.collectAsState()
    val query by placesVm.query.collectAsState()
    val results by placesVm.results.collectAsState()
    val booking by bookingVm.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(config = config, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = placesVm::onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.map_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .semantics { testTag = "map_search" },
            )
            if (results.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column {
                        results.take(3).forEach { place ->
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Same tap feeds BOTH: place history (recents) and
                                        // the active booking slot (P4-4 assign flow).
                                        placesVm.onPlaceSelected(place)
                                        bookingVm.onPlaceTapped(
                                            BookingPlace(place.name, place.lat, place.lng),
                                        )
                                    }
                                    .semantics { testTag = "map_result_${place.placeId}" },
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                if (booking.trip != null) {
                    TripCard(
                        state = booking,
                        onCancel = bookingVm::cancelTrip,
                        onDismissCancelError = bookingVm::clearCancelError,
                    )
                } else {
                    BookingCard(
                        state = booking,
                        recents = recents,
                        onPlace = { place ->
                            bookingVm.onPlaceTapped(place)
                            placesVm.addPlace(place.name, place.lat, place.lng)
                        },
                        onAssignTarget = bookingVm::setAssignTarget,
                        onSelectRideType = bookingVm::selectRideType,
                        onNoteChange = bookingVm::setNote,
                        onPromoChange = bookingVm::setPromo,
                        onBook = bookingVm::book,
                    )
                }
            }
        }
    }
}

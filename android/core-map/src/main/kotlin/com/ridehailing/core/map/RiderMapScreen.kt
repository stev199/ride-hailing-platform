package com.ridehailing.core.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * Rider main screen (P2-2): full-screen self-hosted map, search bar on top, live local
 * search results, recent places strip + ride-type chips in the bottom card. Booking flow
 * and nearby drivers land with dispatch (Phase 5+); v1 proves the map + offline-first
 * place history path end to end.
 */
@Composable
fun RiderMapScreen(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    val vm: PlacesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val recents by vm.recentPlaces.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(config = config, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = vm::onQueryChange,
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
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .semantics { testTag = "map_result_${place.placeId}" },
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Card(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(12.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.map_recent_places),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val recentsText = recents.take(3).joinToString(" · ") { it.name }
                            .ifEmpty { stringResource(R.string.map_recent_empty) }
                        Text(
                            text = recentsText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { testTag = "map_recents" },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(R.string.map_ride_standard, R.string.map_ride_van, R.string.map_ride_lux)
                                .forEach { res ->
                                    var selected by rememberSaveable { mutableStateOf(false) }
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selected = !selected },
                                        label = { Text(stringResource(res)) },
                                    )
                                }
                        }
                        Text(
                            text = stringResource(R.string.map_booking_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

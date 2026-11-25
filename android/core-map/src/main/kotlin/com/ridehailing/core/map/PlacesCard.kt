package com.ridehailing.core.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridehailing.core.data.db.PLACE_SOURCE_SAVED
import com.ridehailing.core.data.db.PLACE_SOURCE_VISITED

/**
 * Places card (P3-3 "visited places"): the offline-first place history bucketed by
 * source, with save/unsave + delete. Adding uses the map center in v1 (map-tap picking
 * lands with the booking flow, Phase 4); the visited bucket is empty by construction
 * until completed rides exist (Phase 4+) and says so honestly.
 */
@Composable
fun PlacesCard(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    val vm: PlacesCardViewModel = hiltViewModel()
    val buckets by vm.buckets.collectAsState()
    var newName by rememberSaveable { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(R.string.places_title), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text(stringResource(R.string.places_addLabel)) },
                modifier = Modifier.fillMaxWidth().semantics { testTag = "places_add_name" },
            )
            Button(
                onClick = {
                    vm.addAt(newName, config.centerLat, config.centerLng)
                    newName = ""
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.semantics { testTag = "places_add_submit" },
            ) {
                Text(stringResource(R.string.places_add))
            }

            PlaceBucket(
                title = stringResource(R.string.places_saved),
                places = buckets.saved,
                emptyText = stringResource(R.string.places_savedEmpty),
                tagPrefix = "places_saved",
                onSaveLabel = stringResource(R.string.places_unsave),
                vm = vm,
            )
            PlaceBucket(
                title = stringResource(R.string.places_recent),
                places = buckets.recent,
                emptyText = stringResource(R.string.places_recentEmpty),
                tagPrefix = "places_recent",
                onSaveLabel = stringResource(R.string.places_save),
                vm = vm,
            )
            PlaceBucket(
                title = stringResource(R.string.places_visited),
                places = buckets.visited,
                emptyText = stringResource(R.string.places_visitedEmpty),
                tagPrefix = "places_visited",
                onSaveLabel = stringResource(R.string.places_save),
                vm = vm,
            )
        }
    }
}

@Composable
private fun PlaceBucket(
    title: String,
    places: List<com.ridehailing.core.data.db.PlaceEntity>,
    emptyText: String,
    tagPrefix: String,
    onSaveLabel: String,
    vm: PlacesCardViewModel,
) {
    Column(modifier = Modifier.semantics { testTag = "$tagPrefix-bucket" }) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (places.isEmpty()) {
            Text(text = emptyText, style = MaterialTheme.typography.bodySmall)
        } else {
            places.take(3).forEach { place ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { testTag = "$tagPrefix-${place.placeId}" },
                    )
                    TextButton(onClick = { vm.toggleSave(place) }) {
                        Text(onSaveLabel)
                    }
                    TextButton(onClick = { vm.delete(place) }) {
                        Text(stringResource(R.string.places_delete))
                    }
                }
            }
        }
    }
}

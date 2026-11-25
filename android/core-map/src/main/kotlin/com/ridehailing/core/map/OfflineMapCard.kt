package com.ridehailing.core.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface OfflineStatus {
    data object Idle : OfflineStatus
    data class Progress(val percent: Int) : OfflineStatus
    data object Done : OfflineStatus
    data class Failed(val reason: String) : OfflineStatus
}

/**
 * Offline maps card (P3-1): download the city extract for airplane-mode use, list
 * stored regions, delete one. Lives OUTSIDE the auth gate (offline-first: an anonymous
 * rider can prepare offline maps too). CI visibility: rendered signed-out in the
 * Settings tab. All composable string formatting happens at composition; the download
 * callbacks only update [OfflineStatus] state.
 */
@Composable
fun OfflineMapCard(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember { OfflineMapManager(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<OfflineStatus>(OfflineStatus.Idle) }
    var regions by remember { mutableStateOf<List<OfflineRegionSummary>>(emptyList()) }

    fun refresh() {
        scope.launch {
            regions = try {
                manager.listRegions()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(R.string.offline_title), style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = {
                    scope.launch {
                        manager.download(config = config, name = "city-extract").collect { result ->
                            status = when (result) {
                                is OfflineDownloadResult.Progress -> OfflineStatus.Progress(result.percent)
                                is OfflineDownloadResult.Success -> OfflineStatus.Done
                                is OfflineDownloadResult.Failure -> OfflineStatus.Failed(result.reason)
                            }
                            if (result !is OfflineDownloadResult.Progress) refresh()
                        }
                    }
                },
                enabled = status !is OfflineStatus.Progress,
                modifier = Modifier.semantics { testTag = "offline_download" },
            ) {
                Text(stringResource(R.string.offline_download))
            }
            when (val s = status) {
                is OfflineStatus.Progress -> Text(
                    text = stringResource(R.string.offline_progress, s.percent),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "offline_status" },
                )
                is OfflineStatus.Done -> Text(
                    text = stringResource(R.string.offline_done),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "offline_status" },
                )
                is OfflineStatus.Failed -> Text(
                    text = stringResource(R.string.offline_failed, s.reason),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "offline_status" },
                )
                OfflineStatus.Idle -> {}
            }
            regions.forEach { region ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { testTag = "offline_region_${region.id}" },
                    )
                    TextButton(onClick = {
                        scope.launch {
                            manager.deleteRegion(region.id)
                            refresh()
                        }
                    }) {
                        Text(stringResource(R.string.offline_delete))
                    }
                }
            }
            if (regions.isEmpty() && status !is OfflineStatus.Progress) {
                Text(
                    text = stringResource(R.string.offline_noRegions),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

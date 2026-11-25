package com.ridehailing.core.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Driver main screen (P2-2): full-screen self-hosted map + duty card (online/offline
 * toggle). Duty sync, beaconing and trip legs land with dispatch (Phase 5+) - the toggle
 * is LOCAL state only in v1 (persisted duty state arrives with the duty backend).
 */
@Composable
fun DriverMapScreen(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    var online by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(config = config, modifier = Modifier.fillMaxSize())
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (online) {
                        stringResource(R.string.map_duty_online)
                    } else {
                        stringResource(R.string.map_duty_offline)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.map_duty_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = online,
                    onCheckedChange = { online = it },
                    modifier = Modifier.semantics { testTag = "duty_toggle" },
                )
            }
        }
    }
}

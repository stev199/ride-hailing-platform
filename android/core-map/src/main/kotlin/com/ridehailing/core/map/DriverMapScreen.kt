package com.ridehailing.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Driver main screen (P2-2 map + P5 flow): full-screen self-hosted map, bottom card
 * switching duty -> dispatch board -> active trip. The duty position v1 = the map
 * center constant (recorded honestly in DriverViewModel); real GPS lands with the
 * location-permission work.
 */
@Composable
fun DriverMapScreen(
    config: MapConfig,
    modifier: Modifier = Modifier,
) {
    val vm: DriverViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        MapScreen(config = config, modifier = Modifier.fillMaxSize())
        DriverBottomCard(
            state = state,
            onToggle = vm::toggleDuty,
            onAccept = vm::accept,
            onAction = vm::tripAction,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

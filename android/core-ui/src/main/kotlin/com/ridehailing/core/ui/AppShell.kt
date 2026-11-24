package com.ridehailing.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

/**
 * Shared app shell frame (P2-1b): mode-localized top bar, body slot and a bottom-bar
 * slot; the NavigationBar itself lives in the apps so each wires its own NavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    mode: AppMode,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    body: @Composable () -> Unit = {},
) {
    val titleRes = when (mode) {
        AppMode.RIDER -> R.string.core_title_rider
        AppMode.DRIVER -> R.string.core_title_driver
    }
    Scaffold(
        modifier = modifier.semantics { testTag = "app_shell" },
        topBar = {
            TopAppBar(title = { Text(stringResource(titleRes)) })
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            body()
        }
    }
}

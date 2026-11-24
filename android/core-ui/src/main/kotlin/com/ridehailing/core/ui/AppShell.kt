package com.ridehailing.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

/**
 * Shared app shell for both actor apps (P2-1a).
 * Renders the mode-localized top bar and a body slot; navigation/Hilt/Room land
 * in P2-1b/1c on top of this shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(mode: AppMode, modifier: Modifier = Modifier, body: @Composable () -> Unit = {}) {
    val titleRes = when (mode) {
        AppMode.RIDER -> R.string.core_title_rider
        AppMode.DRIVER -> R.string.core_title_driver
    }
    Scaffold(
        modifier = modifier.semantics { testTag = "app_shell" },
        topBar = {
            TopAppBar(title = { Text(stringResource(titleRes)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.core_shell_placeholder),
                style = MaterialTheme.typography.bodyLarge,
            )
            body()
        }
    }
}

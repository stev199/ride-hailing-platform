package com.ridehailing.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Shared two-tab app (P2-1b): Home + Settings over Compose Navigation. Both actor apps
 * wire this with their own AppMode; destination bodies are SLOTS since P2-2 (defaults keep
 * the placeholder TabBody) so core-ui stays independent of core-map/core-data. Tab
 * selection follows the back stack entry and every label/icon is resource-resolved
 * (en/fr/ar + RTL transparent).
 */
object TabRoutes {
    const val HOME: String = "home"
    const val SETTINGS: String = "settings"
    const val WALLET: String = "wallet"
    const val EARNINGS: String = "earnings"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeNavApp(
    mode: AppMode,
    homeBody: @Composable () -> Unit = {
        TabBody(text = stringResource(R.string.core_body_home))
    },
    settingsBody: @Composable () -> Unit = {
        TabBody(text = stringResource(R.string.core_body_settings))
    },
    /** P7-3: optional wallet tab - null keeps the shell two-tab (driver app unchanged). */
    walletBody: (@Composable () -> Unit)? = null,
    /** P7-4: optional driver earnings tab - null keeps the shell two-tab (rider app unchanged). */
    earningsBody: (@Composable () -> Unit)? = null,
) {
    val titleRes = when (mode) {
        AppMode.RIDER -> R.string.core_title_rider
        AppMode.DRIVER -> R.string.core_title_driver
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TabRoutes.HOME

    Scaffold(
        modifier = Modifier.semantics { testTag = "app_shell" },
        topBar = { TopAppBar(title = { Text(stringResource(titleRes)) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == TabRoutes.HOME,
                    onClick = { navController.navigateSingleTop(TabRoutes.HOME) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.core_nav_home)) },
                    modifier = Modifier.semantics { testTag = "tab_home" },
                )
                NavigationBarItem(
                    selected = currentRoute == TabRoutes.SETTINGS,
                    onClick = { navController.navigateSingleTop(TabRoutes.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.core_nav_settings)) },
                    modifier = Modifier.semantics { testTag = "tab_settings" },
                )
                if (walletBody != null) {
                    NavigationBarItem(
                        selected = currentRoute == TabRoutes.WALLET,
                        onClick = { navController.navigateSingleTop(TabRoutes.WALLET) },
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) }, // core icon set: wallet glyph is extended-only - Star reads as points
                        label = { Text(stringResource(R.string.core_nav_wallet)) },
                        modifier = Modifier.semantics { testTag = "tab_wallet" },
                    )
                }
                if (earningsBody != null) {
                    NavigationBarItem(
                        selected = currentRoute == TabRoutes.EARNINGS,
                        onClick = { navController.navigateSingleTop(TabRoutes.EARNINGS) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.core_nav_earnings)) },
                        modifier = Modifier.semantics { testTag = "tab_earnings" },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            NavHost(
                navController = navController,
                startDestination = TabRoutes.HOME,
            ) {
                composable(TabRoutes.HOME) {
                    homeBody()
                }
                composable(TabRoutes.SETTINGS) {
                    settingsBody()
                }
                if (walletBody != null) {
                    composable(TabRoutes.WALLET) {
                        walletBody()
                    }
                }
                if (earningsBody != null) {
                    composable(TabRoutes.EARNINGS) {
                        earningsBody()
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

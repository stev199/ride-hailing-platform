package com.ridehailing.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.ridehailing.core.data.outbox.OutboxRepository
import com.ridehailing.core.data.prefs.ThemeStore
import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ModeNavApp
import com.ridehailing.core.ui.RideHailingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Driver shell entry point (P2-1b navigation; P2-1c DI + DataStore theme + outbox drain).
 * Duty/map destinations land in P2-2/P2-4.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeStore: ThemeStore

    @Inject
    lateinit var outbox: OutboxRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Outbox v1: drain on start. No transport is bound yet (Phase 5 wires the network
        // client); the call is a real graph exercise that reports every due row as skipped.
        lifecycleScope.launch { outbox.drain(transport = null) }
        setContent {
            val systemDark = isSystemInDarkTheme()
            // Persisted theme choice (null = follow system). Until the settings screen
            // exists (P2-4) nothing writes a non-null value, so behavior is unchanged.
            val themePref by themeStore.themePreference
                .collectAsState(initial = ThemeStore.THEME_SYSTEM)
            val darkTheme = when (themePref) {
                ThemeStore.THEME_DARK -> true
                ThemeStore.THEME_LIGHT -> false
                else -> systemDark
            }
            RideHailingTheme(mode = AppMode.DRIVER, darkTheme = darkTheme) {
                ModeNavApp(mode = AppMode.DRIVER)
            }
        }
    }
}

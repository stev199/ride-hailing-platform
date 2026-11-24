package com.ridehailing.rider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ModeNavApp
import com.ridehailing.core.ui.RideHailingTheme

/**
 * Rider shell entry point (P2-1b): mode-aware theme + shared two-tab navigation.
 * Booking/map destinations land in P2-2/P2-4.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RideHailingTheme(mode = AppMode.RIDER) {
                ModeNavApp(mode = AppMode.RIDER)
            }
        }
    }
}

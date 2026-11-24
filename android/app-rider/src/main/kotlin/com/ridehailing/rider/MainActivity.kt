package com.ridehailing.rider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.AppShell
import com.ridehailing.core.ui.RideHailingTheme

/**
 * Rider shell entry point (P2-1a). Navigation + auth UI land in P2-1b/1c.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RideHailingTheme {
                AppShell(mode = AppMode.RIDER)
            }
        }
    }
}

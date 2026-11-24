package com.ridehailing.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette (P2-1a baseline; refined with the design pass in P2-1b).
private val RiderBlue = Color(0xFF1565C0)
private val RiderBlueDark = Color(0xFF0D47A1)
private val DriverAmber = Color(0xFFB26A00)
private val Neutral11 = Color(0xFF1B1B1F)
private val Neutral99 = Color(0xFFFDFBFF)

private val LightColors = lightColorScheme(
    primary = RiderBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    background = Neutral99,
    onBackground = Neutral11,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = RiderBlueDark,
    onPrimaryContainer = Color(0xFFD6E3FF),
    background = Neutral11,
    onBackground = Color(0xFFE3E2E6),
)

/**
 * App theme (P2-1a). Dark mode follows the system; the nightly matrix forces both
 * via uiautomator (E2) and screenshot tests pin the result.
 */
@Composable
fun RideHailingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** Marker kept so unit tests can pin the palette revision without touching Compose. */
object ThemeVersion {
    const val SCHEMA: Int = 1
}

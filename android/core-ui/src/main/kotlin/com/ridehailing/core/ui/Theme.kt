package com.ridehailing.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Mode-aware brand palette (P2-1b): rider rides on blue, driver on amber; dark mode
 * follows the system. The nightly matrix forces both uiautomator-side (E2) and
 * screenshot tests pin the result.
 */
private val RiderBlue = Color(0xFF1565C0)
private val RiderBlueDark = Color(0xFF0D47A1)
private val DriverAmber = Color(0xFF9A6A00)
private val DriverAmberDark = Color(0xFF5D4000)
private val Neutral11 = Color(0xFF1B1B1F)
private val Neutral99 = Color(0xFFFDFBFF)

private fun lightColors(primary: Color, primaryDark: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.16f).compositeOverLight(),
    onPrimaryContainer = primaryDark,
    background = Neutral99,
    onBackground = Neutral11,
)

private fun darkColors(primary: Color, container: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF00315F),
    primaryContainer = container,
    onPrimaryContainer = Color(0xFFD6E3FF),
    background = Neutral11,
    onBackground = Color(0xFFE3E2E6),
)

private fun Color.compositeOverLight(): Color =
    Color(
        red = red * alpha + 1f * (1f - alpha),
        green = green * alpha + 1f * (1f - alpha),
        blue = blue * alpha + 1f * (1f - alpha),
        alpha = 1f,
    )

/**
 * App theme (P2-1b). Dark mode follows the system; the primary ramp follows the actor mode.
 */
@Composable
fun RideHailingTheme(
    mode: AppMode,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        mode == AppMode.RIDER && darkTheme -> darkColors(Color(0xFFA6C8FF), RiderBlueDark)
        mode == AppMode.RIDER -> lightColors(RiderBlue, RiderBlueDark)
        darkTheme -> darkColors(Color(0xFFFFB95C), DriverAmberDark)
        else -> lightColors(DriverAmber, DriverAmberDark)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Marker so unit tests pin the palette revision without touching Compose. */
object ThemeVersion {
    const val SCHEMA: Int = 2
}

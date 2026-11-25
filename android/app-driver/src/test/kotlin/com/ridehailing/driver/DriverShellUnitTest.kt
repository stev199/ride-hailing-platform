package com.ridehailing.driver

import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ThemeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trivial JVM smoke tests for the driver shell scaffold (P2-1a / P0-C surface).
 */
class DriverShellUnitTest {
    @Test
    fun driverDefaults_arePinned() {
        assertEquals("Driver", AppMode.DRIVER.defaultLabel)
        assertEquals("Rider", AppMode.RIDER.defaultLabel)
    }

    @Test
    fun modeSchema_andThemeSchema_arePinned() {
        assertEquals(1, AppMode.CURRENT_SCHEMA)
        assertEquals(2, ThemeVersion.SCHEMA)
    }

    @Test
    fun driverAndRider_areDistinctModes() {
        assertFalse(AppMode.DRIVER == AppMode.RIDER)
    }

    @Test
    fun mapStyle_isSelfHosted_only() {
        // Spec hard rule: tiles must come from OUR tile server, never a public/paid host.
        // The emulator default points at the compose geo profile via host loopback.
        val url = BuildConfig.MAP_STYLE_URL
        assertTrue(url.isNotBlank())
        assertTrue(
            "tile style must be plain http(s) to our own server",
            url.startsWith("http://10.0.2.2:8081/") ||
                url.startsWith("http://localhost:8081/") ||
                url.startsWith("http://127.0.0.1:8081/"),
        )
    }
}

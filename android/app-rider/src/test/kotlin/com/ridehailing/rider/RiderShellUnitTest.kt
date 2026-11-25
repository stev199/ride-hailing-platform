package com.ridehailing.rider

import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ThemeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM smoke tests for the rider shell (P2-1a scaffold; P2-2 adds the tile contract).
 */
class RiderShellUnitTest {
    @Test
    fun modeDefaults_arePinned() {
        assertEquals("Rider", AppMode.RIDER.defaultLabel)
        assertEquals("Driver", AppMode.DRIVER.defaultLabel)
    }

    @Test
    fun modeSchema_andThemeSchema_arePinned() {
        assertEquals(1, AppMode.CURRENT_SCHEMA)
        assertEquals(2, ThemeVersion.SCHEMA)
    }

    @Test
    fun riderMode_isOneOfTwoActorModes() {
        assertTrue(AppMode.entries.size == 2 && AppMode.entries.contains(AppMode.RIDER))
    }

    @Test
    fun mapStyle_isSelfHosted_only() {
        // Spec hard rule: tiles must come from OUR tile server, never a public/paid host.
        // The emulator default points at the compose geo profile via host loopback.
        val url = BuildConfig.MAP_STYLE_URL
        assertTrue(url.isNotBlank())
        assertTrue("tile style must be plain http(s) to our own server", url.startsWith("http://10.0.2.2:8081/") || url.startsWith("http://localhost:8081/") || url.startsWith("http://127.0.0.1:8081/"))
    }

    @Test
    fun apiBase_isSelfHosted_only() {
        // P2-4: the API client talks to OUR backend only (self-hosted compose stack or
        // dev machine) - never a third-party service. Same contract as the tile test.
        val url = BuildConfig.API_BASE_URL
        assertTrue(url.isNotBlank())
        assertTrue(
            "api base must be plain http(s) to our own server",
            url.startsWith("http://10.0.2.2:8080") ||
                url.startsWith("http://localhost:8080") ||
                url.startsWith("http://127.0.0.1:8080"),
        )
    }
}

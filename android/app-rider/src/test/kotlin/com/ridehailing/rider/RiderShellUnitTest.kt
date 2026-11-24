package com.ridehailing.rider

import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ThemeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trivial JVM smoke tests for the rider shell scaffold (P2-1a / P0-C surface).
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
}

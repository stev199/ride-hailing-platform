package com.ridehailing.driver

import com.ridehailing.core.ui.AppMode
import com.ridehailing.core.ui.ThemeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(1, ThemeVersion.SCHEMA)
    }

    @Test
    fun driverAndRider_areDistinctModes() {
        assertFalse(AppMode.DRIVER == AppMode.RIDER)
    }
}

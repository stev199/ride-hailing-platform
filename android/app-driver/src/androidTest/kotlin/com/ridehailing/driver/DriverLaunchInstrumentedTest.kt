package com.ridehailing.driver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Trivial instrumented test (P0-C first-green surface): the driver shell launches and
 * shows the driver title. Locale-agnostic (expected text resolved from resources) so the
 * nightly matrix (en/fr/ar) and RTL cells all pass; dark/light is theme-transparent.
 */
@RunWith(AndroidJUnit4::class)
class DriverLaunchInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shellLaunches_withLocalizedDriverTitle() {
        composeRule.waitForIdle()
        val expected = composeRule.activity.getString(R.string.core_title_driver)
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}

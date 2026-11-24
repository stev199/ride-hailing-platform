package com.ridehailing.driver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests (P0-C first-green surface + P2-1b navigation): the driver shell
 * launches with the driver title and the Home/Settings tabs navigate both ways.
 * Locale-agnostic (expected text resolved from resources) so the nightly matrix
 * (en/fr/ar) and RTL cells all pass; dark/light is theme-transparent.
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

    @Test
    fun navigationSwitchesToSettings_andBack() {
        composeRule.waitForIdle()
        val activity = composeRule.activity

        composeRule.onNodeWithText(activity.getString(R.string.core_nav_settings))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(activity.getString(R.string.core_body_settings))
            .assertIsDisplayed()

        composeRule.onNodeWithText(activity.getString(R.string.core_nav_home))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(activity.getString(R.string.core_body_home))
            .assertIsDisplayed()
    }
}

package com.ridehailing.driver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.ui.R
import com.ridehailing.test.storyboard.StoryboardRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests (P0-C first-green surface + P2-1b navigation + P2-3 storyboard):
 * the driver shell launches with the driver title and the Home/Settings tabs navigate
 * both ways. Every meaningful action is wrapped in step() (VISIBILITY_SPEC E2) so the
 * run's report gallery shows the storyboard filmstrip; the StoryboardRule fails any
 * test that finishes with zero step() calls. Locale-agnostic (expected text resolved
 * from resources) so the nightly matrix (en/fr/ar) and RTL cells all pass.
 */
@RunWith(AndroidJUnit4::class)
class DriverLaunchInstrumentedTest {
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Outermost (order 2): begin runs before the compose-rule activity launch and the
    // FAIL capture sees compose assertion failures (VISIBILITY_SPEC E2).
    @get:Rule(order = 2)
    val storyboard = StoryboardRule(app = "driver")

    @Test
    fun shellLaunches_withLocalizedDriverTitle() {
        storyboard.step("Driver shell shows the localized title") {
            composeRule.waitForIdle()
            val expected = composeRule.activity.getString(R.string.core_title_driver)
            composeRule.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun navigationSwitchesToSettings_andBack() {
        val activity = composeRule.activity

        storyboard.step("Switch to the Settings tab") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(activity.getString(R.string.core_body_settings))
                .assertIsDisplayed()
        }

        storyboard.step("Return to the Home tab") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(activity.getString(R.string.core_body_home))
                .assertIsDisplayed()
        }
    }
}

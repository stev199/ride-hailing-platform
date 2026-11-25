package com.ridehailing.driver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.map.R as MapR
import com.ridehailing.core.screens.R as ScreensR
import com.ridehailing.core.ui.R
import com.ridehailing.test.storyboard.StoryboardRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests (P0-C first-green surface + P2-1b navigation + P2-2 map + P2-3
 * storyboard): the driver shell launches with the driver title and the Home/Settings
 * tabs navigate both ways. Since P2-2 the Home tab is the DRIVER MAP SCREEN (the P2-1b
 * placeholder is gone) - the navigation test asserts the duty card's offline text
 * instead: resource-resolved (locale-agnostic, en/fr/ar + RTL safe) and INDEPENDENT of
 * map tiles (the card renders with or without the self-hosted tile server reachable).
 * Every meaningful action is wrapped in step() (VISIBILITY_SPEC E2); StoryboardRule
 * fails any test that finishes with zero step() calls.
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

        storyboard.step("Switch to the Settings tab - session screen (P2-4)") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
            // P2-4: the Settings tab is the SessionScreen now; signed-out it shows the
            // login card. Emulator runs carry no session -> the login SUBTITLE shows
            // (unique node: auth_title "Sign in" also matches the submit button).
            composeRule.onNodeWithText(activity.getString(ScreensR.string.auth_subtitle))
                .assertIsDisplayed()
        }

        storyboard.step("Return to the Home tab - driver map screen") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(activity.getString(MapR.string.map_duty_offline))
                .assertIsDisplayed()
        }
    }
}

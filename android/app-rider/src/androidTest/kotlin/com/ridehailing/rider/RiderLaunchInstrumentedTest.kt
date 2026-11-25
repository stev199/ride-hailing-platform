package com.ridehailing.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.map.R as MapR
import com.ridehailing.core.ui.R
import com.ridehailing.test.storyboard.StoryboardRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests (P0-C first-green surface + P2-1b navigation + P2-2 map + P2-3
 * storyboard): the rider shell launches with the rider title and the Home/Settings tabs
 * navigate both ways. Since P2-2 the Home tab is the RIDER MAP SCREEN (the P2-1b
 * placeholder is gone) - the navigation test asserts the map screen's booking-card text
 * instead: resource-resolved (locale-agnostic, en/fr/ar + RTL safe) and INDEPENDENT of
 * map tiles (the card renders with or without the self-hosted tile server reachable).
 * Every meaningful action is wrapped in step() (VISIBILITY_SPEC E2); StoryboardRule
 * fails any test that finishes with zero step() calls.
 */
@RunWith(AndroidJUnit4::class)
class RiderLaunchInstrumentedTest {
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Outermost (order 2): begin runs before the compose-rule activity launch and the
    // FAIL capture sees compose assertion failures (VISIBILITY_SPEC E2).
    @get:Rule(order = 2)
    val storyboard = StoryboardRule(app = "rider")

    @Test
    fun shellLaunches_withLocalizedRiderTitle() {
        storyboard.step("Rider shell shows the localized title") {
            composeRule.waitForIdle()
            val expected = composeRule.activity.getString(R.string.core_title_rider)
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

        storyboard.step("Return to the Home tab - rider map screen") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(activity.getString(MapR.string.map_booking_placeholder))
                .assertIsDisplayed()
        }
    }
}

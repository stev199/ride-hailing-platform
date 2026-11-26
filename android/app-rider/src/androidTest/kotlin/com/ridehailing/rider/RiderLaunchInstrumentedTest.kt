package com.ridehailing.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.screens.R as ScreensR
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

        storyboard.step("Switch to the Settings tab - session screen (P2-4)") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
            // Round-15m: taps are intermittently eaten on this emulator (the login
            // saga proved it) - if the pane did not switch in 3s, invoke the tab's
            // OnClick semantics directly (accessibility-equivalent).
            run {
                var switched = false
                val dl = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < dl) {
                    if (composeRule.onAllNodes(
                            SemanticsMatcher("any-node") { true },
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes().any {
                            it.config.getOrNull(
                                androidx.compose.ui.semantics.SemanticsProperties.TestTag,
                            ) == "offline_download"
                        }
                    ) {
                        switched = true
                        break
                    }
                    Thread.sleep(300)
                }
                if (!switched) {
                    composeRule.onNodeWithTag("tab_settings", useUnmergedTree = true)
                        .performSemanticsAction(
                            androidx.compose.ui.semantics.SemanticsActions.OnClick,
                        )
                }
            }
            // P2-4: the Settings tab is the SessionScreen now. Since the booking e2e
            // logs in FOR REAL (round 15l+) and the shard shares the app's DataStore,
            // this test may run SIGNED-IN: assert the offline-maps card - it renders
            // OUTSIDE the auth gate in BOTH states (signed-out login card / signed-in
            // session card).
            composeRule.onNodeWithTag("offline_download", useUnmergedTree = true)
                .assertIsDisplayed()
        }

        storyboard.step("Return to the Home tab - rider map screen") {
            composeRule.onNodeWithText(activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            // Round-15m: same intermittent-tap fallback for the return trip.
            run {
                var switched = false
                val dl = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < dl) {
                    if (composeRule.onAllNodes(
                            SemanticsMatcher("any-node") { true },
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes().any {
                            it.config.getOrNull(
                                androidx.compose.ui.semantics.SemanticsProperties.TestTag,
                            ) == "booking_pickup"
                        }
                    ) {
                        switched = true
                        break
                    }
                    Thread.sleep(300)
                }
                if (!switched) {
                    composeRule.onNodeWithTag("tab_home", useUnmergedTree = true)
                        .performSemanticsAction(
                            androidx.compose.ui.semantics.SemanticsActions.OnClick,
                        )
                }
            }
            // P4-4: the bottom card is the REAL booking flow now (the old Phase-5
            // placeholder string is gone) - the pickup row is the card's first node.
            composeRule.onNodeWithTag("booking_pickup", useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }
}

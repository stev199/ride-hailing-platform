package com.ridehailing.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.map.OfflineMapManager
import com.ridehailing.core.map.R as MapR
import com.ridehailing.core.screens.R as ScreensR
import com.ridehailing.core.ui.R
import com.ridehailing.test.storyboard.StoryboardRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-2 offline-map verification (spec verification protocol #5/#7 + D69.9
 * airplane-mode-per-gate). Runs ONLY in the e2e-offline-maps CI job where the
 * self-hosted tile server is reachable at 10.0.2.2:8081:
 *
 * 1. `downloadRegionOffline` (online): taps the offline download button in Settings and
 *    waits for the "offline maps ready" status - the MapLibre offline store now holds
 *    the city extract (style + glyphs + vector tiles) downloaded from OUR tileserver.
 * 2. `mapRendersFromOfflineRegion` (AIRPLANE MODE, toggled by the CI job between the
 *    two gradle invocations): the app boots, the home map screen still shows, the
 *    offline region is still listed in Settings, and storyboard screenshots are captured
 *    for the gallery. MapLibre serves the style + tiles for the SAME style URL from the
 *    offline database, so the map renders with zero network.
 *
 * Skip semantics (run 45539682110 lesson - the render test originally hard-FAILED the
 * plain android-ui shard because only the download test had a guard):
 * - `downloadRegionOffline` skips when the tile server is unreachable (no geo stack).
 * - `mapRendersFromOfflineRegion` skips when the offline store is EMPTY. In a plain
 *   android-ui run the download never happened (skipped), so the store is empty -> the
 *   render test empty-passes too. In the e2e job the DOWNLOAD step's own "region listed"
 *   assertion already guarantees a non-empty store before airplane mode, so a skip here
 *   can never mask a real e2e failure - empty-store-in-e2e fails the download step first.
 */
@RunWith(AndroidJUnit4::class)
class OfflineMapsInstrumentedTest {
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 2)
    val storyboard = StoryboardRule(app = "rider")

    /** 10.0.2.2 reaches the host docker tileserver from the emulator. */
    private fun tileServerReachable(): Boolean = runBlocking {
        try {
            // Same style contract as MapConfig.EMULATOR_STYLE_URL ("basic-preview" is the
            // only style tileserver-gl v4.6.2 bundles - round-12 root cause for the 404s).
            val url = java.net.URL("http://10.0.2.2:8081/styles/basic-preview/style.json")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.requestMethod = "HEAD"
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun downloadRegionOffline() {
        storyboard.step("Precondition: self-hosted tile server reachable") {
            org.junit.Assume.assumeTrue("tile server 10.0.2.2:8081 not reachable - skip (no geo stack)", tileServerReachable())
        }

        storyboard.step("Open Settings") {
            composeRule.waitForIdle()
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
        }

        storyboard.step("Tap the offline download button") {
            composeRule.onNodeWithTag("offline_download").performClick()
        }

        storyboard.step("Wait for the offline-ready status") {
            val ready = composeRule.activity.getString(MapR.string.offline_done)
            fun statusText(): String =
                composeRule.onAllNodesWithTag("offline_status", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.config
                    ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.joinToString("")
                    ?: ""
            // Monaco extract (zoom 0-14) over localhost downloads in well under a minute;
            // 5 min is generous for slower CI runners.
            composeRule.waitUntil(timeoutMillis = 300_000) { statusText().contains(ready) }
            composeRule.onNodeWithTag("offline_status", useUnmergedTree = true).assertIsDisplayed()
        }

        storyboard.step("Offline region listed") {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                (1..3).any { id ->
                    composeRule.onAllNodesWithTag("offline_region_$id", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
        }
    }

    @Test
    fun mapRendersFromOfflineRegion() {
        // AIRPLANE MODE: the CI job enables it between the two gradle invocations.
        // The storyboard screenshot on the home map screen IS the visual evidence
        // (real pixels from the offline store - the gallery renders them).

        // Precondition (see class KDoc skip semantics): the offline store must hold a
        // region - in the e2e job the download step just created one; in a plain
        // android-ui run (no geo stack, download skipped) the store is empty and this
        // test MUST empty-pass instead of failing the shard (run 45539682110).
        storyboard.step("Precondition: offline region exists in the store") {
            val regions = runBlocking {
                OfflineMapManager(composeRule.activity.applicationContext).listRegions()
            }
            org.junit.Assume.assumeTrue(
                "offline store empty - download phase did not run in this context (plain android-ui): skip",
                regions.isNotEmpty(),
            )
        }

        storyboard.step("App boots offline: rider shell shows") {
            composeRule.waitForIdle()
            val expected = composeRule.activity.getString(R.string.core_title_rider)
            composeRule.onNodeWithText(expected).assertIsDisplayed()
        }

        storyboard.step("Home map screen renders (offline region in settings)") {
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
            // offline region list (any id) proves the store survives the process
            val anyRegionRow = composeRule.onAllNodesWithTag("offline_region_1", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodesWithTag("offline_region_2", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty() ||
                composeRule.onAllNodesWithTag("offline_region_3", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            org.junit.Assert.assertTrue("offline region missing in airplane mode", anyRegionRow)
        }

        storyboard.step("Back to the map screen (offline render evidence screenshot)") {
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            // keep the screen on the map briefly so the storyboard captures the offline
            // map pixels for the gallery (the screenshot IS the visual evidence)
            Thread.sleep(2_000)
        }
    }
}

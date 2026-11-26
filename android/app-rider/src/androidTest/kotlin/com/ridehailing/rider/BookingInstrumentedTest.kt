package com.ridehailing.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.data.db.AppDatabase
import com.ridehailing.core.data.db.PlaceEntity
import com.ridehailing.core.data.db.PLACE_SOURCE_RECENT
import com.ridehailing.core.ui.R
import com.ridehailing.rider.BuildConfig
import com.ridehailing.test.storyboard.StoryboardRule
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4-5 booking e2e (spec §delivery plan 4): register -> login -> assign pickup/dropoff
 * from LOCAL place search -> estimate (backend math, straight-line fallback in the test
 * profile: no OSRM) -> book -> trip screen shows the server-SIGNED quote (201) ->
 * cancel -> CANCELLED. Runs ONLY in the e2e-booking CI job where the compose test
 * stack (backend :8080) is reachable at 10.0.2.2:8080 - everywhere else it skips
 * (same skip semantics as OfflineMapsInstrumentedTest).
 *
 * Quote-expiry honesty note: the expiry edge is covered SERVER-SIDE by the P4-2 rides
 * tests (90s window); this cell asserts the quote + validity render, it does not idle
 * 90s to watch the UI flip to expired.
 */
@RunWith(AndroidJUnit4::class)
class BookingInstrumentedTest {
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 2)
    val storyboard = StoryboardRule(app = "rider")

    private fun backendReachable(): Boolean = runBlocking {
        try {
            val conn = URL(BuildConfig.API_BASE_URL.removeSuffix("/") + "/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** Register (201) or reuse (409) - either way the login below then works. */
    private fun registerRider(phone: String, password: String): Int = try {
        val conn = URL(BuildConfig.API_BASE_URL.removeSuffix("/") + "/v1/auth/register")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use {
            it.write(
                ("""{"displayName":"Booking Rider","password":"$password","phone":"$phone"}""")
                    .toByteArray(),
            )
        }
        conn.responseCode
    } catch (_: Exception) {
        -1
    }

    /** Seed the LOCAL place history the search runs against (offline-first search). */
    private fun seedPlaces() {
        val context = composeRule.activity.applicationContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val now = System.currentTimeMillis()
            runBlocking {
                db.placeDao().upsert(
                    PlaceEntity(
                        placeId = "e2e-harbor", name = "Harbor", lat = 43.7384, lng = 7.4246,
                        source = PLACE_SOURCE_RECENT, lastUsedMs = now,
                    ),
                )
                db.placeDao().upsert(
                    PlaceEntity(
                        placeId = "e2e-airport", name = "Airport", lat = 43.7543, lng = 7.4312,
                        source = PLACE_SOURCE_RECENT, lastUsedMs = now - 1,
                    ),
                )
            }
        } finally {
            db.close()
        }
    }

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun searchAndTap(query: String, placeId: String) {
        composeRule.onNodeWithTag("map_search").performTextReplacement(query)
        // Local LIKE search is synchronous enough; wait for the row then tap.
        composeRule.waitUntil(timeoutMillis = 10_000) { nodeExists("map_result_$placeId") }
        // Round-15g: close the IME first - it covers the lower half and the result
        // row (or the booking card behind it) can sit behind the keyboard.
        Espresso.closeSoftKeyboard()
        try {
            composeRule.onNodeWithTag("map_result_$placeId").performScrollTo()
        } catch (_: Throwable) {  // AssertionError is an Error, not an Exception
            // No scrollable ancestor - already visible.
        }
        composeRule.onNodeWithTag("map_result_$placeId").assertIsDisplayed()
        composeRule.onNodeWithTag("map_result_$placeId").performClick()
    }

    @Test
    fun bookingFlowEndToEnd() {
        storyboard.step("Precondition: backend compose stack reachable") {
            org.junit.Assume.assumeTrue(
                "backend 10.0.2.2:8080 not reachable - skip (no test stack)",
                backendReachable(),
            )
        }

        val phone = instrumentedArg("e2ePhone") ?: "+37760000001"
        val password = instrumentedArg("e2ePassword") ?: "booking-e2e-pass-42"

        storyboard.step("Seed local places + register rider") {
            seedPlaces()
            val code = registerRider(phone, password)
            org.junit.Assert.assertTrue(
                "register refused (code=$code, 201/409 expected)",
                code == 201 || code == 409,
            )
        }

        storyboard.step("Login via the settings card") {
            composeRule.waitForIdle()
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_settings))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("login_identifier").performTextInput(phone)
            composeRule.onNodeWithTag("login_password").performTextInput(password)
            // Round-15f probe A: prove the typing actually landed (CI #193-#199 showed
            // a click that did nothing at all - neither busy, error nor session; an
            // EMPTY field set would disable the button and explain every symptom).
            val typedId = composeRule.onNodeWithTag("login_identifier")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
            val typedPw = composeRule.onNodeWithTag("login_password")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
            org.junit.Assert.assertTrue(
                "typing did not land: identifier='$typedId' password='$typedPw'",
                !typedId.isNullOrEmpty(),
            )
            // Round-15g ROOT CAUSE (CI #195-#201): the IME covers the bottom half of the
            // screen after typing - the injected submit tap landed ON THE KEYBOARD (probe
            // proved: fields typed, button enabled, click = complete no-op, zero HTTP
            // lines). Hide the keyboard, bring the button into view, prove it is
            // displayed, THEN click.
            Espresso.closeSoftKeyboard()
            try {
                composeRule.onNodeWithTag("login_submit").performScrollTo()
            } catch (_: Throwable) {  // AssertionError is an Error, not an Exception
                // No scrollable ancestor - the card is already on screen.
            }
            composeRule.onNodeWithTag("login_submit").assertIsDisplayed()
            composeRule.onNodeWithTag("login_submit").performClick()
            // Round-15 fail-fast: poll for session_info OR login_error - a failed
            // login must name its error instead of burning the 20s waitUntil blind
            // (the base-URL bug burned exactly this wait on CI #191).
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (nodeExists("session_info")) break
                if (nodeExists("login_error")) {
                    org.junit.Assert.fail("login failed on-screen: '${firstNodeText("login_error")}'")
                }
                Thread.sleep(500)
            }
            if (!nodeExists("session_info")) {
                // Round-15c: name the actual screen at timeout - dump every visible
                // test tag so a hung/crashed/mis-routed login is diagnosable from CI.
                val tags = composeRule.onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher("any-node") { true },
                    useUnmergedTree = true,
                )
                    .fetchSemanticsNodes()
                    .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                    .distinct()
                // Round-15f probe B: was the submit button even enabled, and what is
                // in the fields NOW (a remount between typing and clicking wipes
                // rememberSaveable state - the numbers point at that instantly).
                val submitNode = composeRule.onNodeWithTag("login_submit", useUnmergedTree = true)
                    .fetchSemanticsNode()
                // Compose semantics: DISABLED is the PRESENCE of Disabled (no Enabled prop).
                val submitEnabled = submitNode.config.getOrNull(SemanticsProperties.Disabled) == null
                val idNow = composeRule.onNodeWithTag("login_identifier", useUnmergedTree = true)
                    .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
                org.junit.Assert.fail(
                    "no session card 20s after login; submit enabled=${submitEnabled ?: "unset"}; " +
                        "identifier now='$idNow'; on-screen tags: ${tags.joinToString()}"
                )
            }
            // Round-15b: the tag lives in the UNMERGED tree (the session Card merges
            // descendants) - nodeExists() polls unmerged, the final assert must too
            // (CI #193: merged-tree assert failed right after the loop saw the node).
            composeRule.onNodeWithTag("session_info", useUnmergedTree = true).assertIsDisplayed()
        }

        storyboard.step("Back to the map, assign pickup from search") {
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            searchAndTap("Harbor", "e2e-harbor")
            // The pickup row flips from the empty hint to "Pickup: <label>" (EN locale).
            composeRule.waitUntil(timeoutMillis = 10_000) {
                firstNodeText("booking_pickup").contains("Harbor")
            }
        }

        storyboard.step("Assign dropoff from search") {
            searchAndTap("Airport", "e2e-airport")
        }

        storyboard.step("Estimate arrives with ride-type chips + permanent disclaimer") {
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("booking_ride_standard") }
            composeRule.onNodeWithTag("booking_ride_standard").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) { nodeExists("booking_estimate_range") }
            composeRule.waitUntil(timeoutMillis = 10_000) { nodeExists("booking_disclaimer") }
        }

        storyboard.step("Book -> trip screen with the server-signed quote") {
            composeRule.onNodeWithTag("booking_book").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("trip_status") }
            composeRule.onNodeWithTag("trip_status", useUnmergedTree = true).assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("trip_fare") }
            composeRule.onNodeWithTag("trip_fare", useUnmergedTree = true).assertIsDisplayed()
        }

        storyboard.step("Cancel -> CANCELLED") {
            composeRule.waitUntil(timeoutMillis = 20_000) { nodeExists("trip_cancel") }
            composeRule.onNodeWithTag("trip_cancel").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("trip_cancelled") }
            composeRule.onNodeWithTag("trip_cancelled", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private fun firstNodeText(tag: String): String =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("")
            ?: ""

    private fun instrumentedArg(key: String): String? =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(key)
}

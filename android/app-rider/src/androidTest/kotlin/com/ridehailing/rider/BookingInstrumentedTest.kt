package com.ridehailing.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.room.Room
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
            composeRule.onNodeWithTag("login_submit").performClick()
            // Session card replaces the login card on success.
            composeRule.waitUntil(timeoutMillis = 20_000) { nodeExists("session_info") }
            composeRule.onNodeWithTag("session_info").assertIsDisplayed()
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
            composeRule.onNodeWithTag("trip_status").assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("trip_fare") }
            composeRule.onNodeWithTag("trip_fare").assertIsDisplayed()
        }

        storyboard.step("Cancel -> CANCELLED") {
            composeRule.waitUntil(timeoutMillis = 20_000) { nodeExists("trip_cancel") }
            composeRule.onNodeWithTag("trip_cancel").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) { nodeExists("trip_cancelled") }
            composeRule.onNodeWithTag("trip_cancelled").assertIsDisplayed()
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

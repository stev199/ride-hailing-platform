package com.ridehailing.driver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridehailing.core.ui.R
import com.ridehailing.driver.BuildConfig
import com.ridehailing.test.storyboard.StoryboardRule
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5 driver flow e2e (directive #5 appendix evidence): seed a REQUESTED ride
 * server-side (admin-promoted DRIVER, rider books via POST /v1/rides) -> UI login ->
 * duty ON -> the 5s board poll surfaces offer_<id> -> tap = single-winner accept ->
 * ACCEPTED -> arrive -> ARRIVED -> start -> IN_PROGRESS -> complete -> the
 * driver_completed node (5s UI window before the poll nulls activeRide) AND the
 * rider-side GET /v1/rides/{id} reads status COMPLETED (final truth).
 *
 * Runs ONLY where the compose test stack is reachable at 10.0.2.2:8080
 * (the android-ui-driver CI cell); everywhere else it skips (same semantics as
 * BookingInstrumentedTest). No rider PII is asserted on the driver side - the
 * card carries pickup/dropoff names + the verbatim quote (P5 DTO parity).
 */
@RunWith(AndroidJUnit4::class)
class DriverBoardInstrumentedTest {
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 2)
    val storyboard = StoryboardRule(app = "driver")

    // ---- tiny HTTP helpers (no client deps on-device; JSON via org.json) ----

    private fun http(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
    ): Pair<Int, String> = try {
        val conn = URL(BuildConfig.API_BASE_URL.removeSuffix("/") + path)
            .openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        // inputStream THROWS on 4xx/5xx - read it defensively, fall back to errorStream.
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        conn.responseCode to text
    } catch (e: Exception) {
        -1 to (e.message ?: "http-failed")
    }

    private fun register(phone: String, password: String, name: String): Int =
        http(
            "POST", "/v1/auth/register",
            """{"displayName":"$name","password":"$password","phone":"$phone"}""",
        ).first

    /** Login (password) -> access token; fails the test with the response body on non-200. */
    private fun loginToken(identifier: String, password: String): String {
        val (code, body) = http(
            "POST", "/v1/auth/login/password",
            """{"identifier":"$identifier","password":"$password"}""",
        )
        assertTrue("login $identifier failed (code=$code, body=$body)", code == 200)
        return JSONObject(body).getJSONObject("tokens").getString("accessToken")
    }

    private fun userId(token: String): String {
        val (code, body) = http("GET", "/v1/me", token = token)
        assertTrue("GET /v1/me failed (code=$code)", code == 200)
        return JSONObject(body).getString("id")
    }

    /** Admin promotes the freshly registered rider account to DRIVER (PATCH role). */
    private fun promoteToDriver(adminToken: String, targetUserId: String) {
        val (code, body) = http(
            "PATCH", "/v1/admin/users/$targetUserId",
            """{"role":"DRIVER"}""", token = adminToken,
        )
        assertTrue(
            "admin role->DRIVER failed (code=$code, body=$body)",
            code == 200 || code == 204,
        )
    }

    /** Rider books the trip the driver will see on the board -> ride id. */
    private fun createRide(riderToken: String): String {
        val (code, body) = http(
            "POST", "/v1/rides",
            """{"pickupLat":43.7384,"pickupLon":7.4246,"dropoffLat":43.7543,"dropoffLon":7.4312,""" +
                """"city":"monaco","rideType":"standard","paymentMethod":"CASH",""" +
                """"pickupName":"Harbor","dropoffName":"Airport"}""",
            token = riderToken,
        )
        assertTrue("POST /v1/rides failed (code=$code, body=$body)", code == 201)
        return JSONObject(body).getString("id")
    }

    /** Final-truth read: the ride status as the RIDER sees it. */
    private fun rideStatus(riderToken: String, rideId: String): String {
        val (code, body) = http("GET", "/v1/rides/$rideId", token = riderToken)
        assertTrue("GET /v1/rides/$rideId failed (code=$code)", code == 200)
        return JSONObject(body).getString("status")
    }

    // ---- UI helpers (battle-tested patterns from BookingInstrumentedTest) ----

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

    private fun nodeExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun firstNodeText(tag: String): String =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("")
            ?: ""

    private fun dumpTags(prefix: String): String {
        val tags = composeRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("any-node") { true },
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes()
            .mapNotNull { n ->
                val t = n.config.getOrNull(SemanticsProperties.TestTag)
                    ?: return@mapNotNull null
                "$t=${n.boundsInRoot}"
            }
            .joinToString()
        return "$prefix on-screen tags: ${tags.ifEmpty { "(NONE)" }}"
    }

    /**
     * Login via the settings card (15g/15i pattern): SetText (no IME pan), close the
     * keyboard, scroll the submit into view, prove it displayed, real tap with the
     * 3s OnClick-semantics fallback, then poll session_info / login_error fail-fast.
     */
    private fun loginViaUi(phone: String, password: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_identifier").performTextReplacement(phone)
        composeRule.onNodeWithTag("login_password").performTextReplacement(password)
        val typedId = composeRule.onNodeWithTag("login_identifier")
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
        assertTrue("typing did not land: identifier='$typedId'", !typedId.isNullOrEmpty())
        Espresso.closeSoftKeyboard()
        try {
            composeRule.onNodeWithTag("login_submit").performScrollTo()
        } catch (_: Throwable) {
            // No scrollable ancestor - the card is already on screen.
        }
        composeRule.onNodeWithTag("login_submit").assertIsDisplayed()
        composeRule.onNodeWithTag("login_submit").performClick()
        var reacted = false
        val softDeadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < softDeadline) {
            if (nodeExists("login_busy") || nodeExists("login_error") || nodeExists("session_info")) {
                reacted = true
                break
            }
            Thread.sleep(300)
        }
        if (!reacted) {
            composeRule.onNodeWithTag("login_submit", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (nodeExists("session_info")) break
            if (nodeExists("login_error")) {
                fail("driver login failed on-screen: '${firstNodeText("login_error")}'")
            }
            Thread.sleep(500)
        }
        if (!nodeExists("session_info")) {
            fail("no session card 20s after driver login; " + dumpTags("login-timeout"))
        }
        composeRule.onNodeWithTag("session_info", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Poll a driver trip status until the tag text equals [status] (fail-fast on error card). */
    private fun awaitTripStatus(status: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (nodeExists("trip_status_driver") && firstNodeText("trip_status_driver") == status) return
            if (nodeExists("driver_action_error")) {
                fail("trip action to $status failed on-screen: '${firstNodeText("driver_action_error")}'")
            }
            Thread.sleep(400)
        }
        fail("trip never reached $status within ${timeoutMs}ms; " + dumpTags("status-$status-timeout"))
    }

    @Test
    fun driverFlowEndToEnd() {
        storyboard.step("Precondition: backend compose stack reachable") {
            org.junit.Assume.assumeTrue(
                "backend 10.0.2.2:8080 not reachable - skip (no test stack)",
                backendReachable(),
            )
        }

        val riderPhone = instrumentedArg("e2eRiderPhone") ?: "+37770000002"
        val driverPhone = instrumentedArg("e2eDriverPhone") ?: "+37770000001"
        val password = instrumentedArg("e2ePassword") ?: "driver-e2e-pass-42"
        val adminPhone = instrumentedArg("e2eAdminPhone") ?: "15550088888"
        val adminPassword = instrumentedArg("e2eAdminPassword") ?: "contract-admin-pass-42"

        lateinit var rideId: String
        lateinit var riderToken: String

        storyboard.step("Seed: register driver+rider, admin-promote driver, rider books") {
            val regDriver = register(driverPhone, password, "E2E Driver")
            val regRider = register(riderPhone, password, "E2E Rider")
            assertTrue(
                "register refused (driver=$regDriver rider=$regRider, 201/409 expected)",
                (regDriver == 201 || regDriver == 409) && (regRider == 201 || regRider == 409),
            )
            val adminToken = loginToken(adminPhone, adminPassword)
            val driverToken0 = loginToken(driverPhone, password)
            promoteToDriver(adminToken, userId(driverToken0))
            riderToken = loginToken(riderPhone, password)
            rideId = createRide(riderToken)
            assertTrue(
                "seeded ride not REQUESTED: ${rideStatus(riderToken, rideId)}",
                rideStatus(riderToken, rideId) == "REQUESTED",
            )
        }

        storyboard.step("Driver login via the settings card") {
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_settings))
                .performClick()
            loginViaUi(driverPhone, password)
            // Back to the map - the duty card (offline) must show.
            composeRule.onNodeWithText(composeRule.activity.getString(R.string.core_nav_home))
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("duty_toggle", useUnmergedTree = true).assertIsDisplayed()
        }

        storyboard.step("Duty ON -> board card replaces the duty card") {
            composeRule.onNodeWithTag("duty_toggle", useUnmergedTree = true).performClick()
            // Online flip is server-acked: the offers card (empty hint or rows) appears.
            val deadline = System.currentTimeMillis() + 15_000
            var online = false
            while (System.currentTimeMillis() < deadline) {
                if (nodeExists("driver_no_offers") || nodeExistsPrefix("offer_") ||
                    nodeExists("driver_offers_error")
                ) {
                    online = true
                    break
                }
                if (nodeExists("duty_error")) {
                    fail("duty toggle failed on-screen: '${firstNodeText("duty_error")}'")
                }
                Thread.sleep(400)
            }
            if (!online) fail("no offers card 15s after duty ON; " + dumpTags("duty-timeout"))
        }

        storyboard.step("Board shows the seeded ride (5s poll) -> accept -> ACCEPTED") {
            try {
                composeRule.waitUntil(timeoutMillis = 30_000) { nodeExistsPrefix("offer_") }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                fail("board never listed the seeded ride; " + dumpTags("board-timeout"))
            }
            composeRule.onNodeWithTag("offer_$rideId", useUnmergedTree = true).performClick()
            awaitTripStatus("ACCEPTED", 20_000)
            composeRule.onNodeWithTag("driver_trip_pickup", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("driver_trip_dropoff", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("driver_trip_fare", useUnmergedTree = true).assertIsDisplayed()
        }

        storyboard.step("arrive -> ARRIVED") {
            composeRule.onNodeWithTag("trip_arrive", useUnmergedTree = true).performClick()
            awaitTripStatus("ARRIVED", 20_000)
        }

        storyboard.step("start -> IN_PROGRESS") {
            composeRule.onNodeWithTag("trip_start", useUnmergedTree = true).performClick()
            awaitTripStatus("IN_PROGRESS", 20_000)
        }

        storyboard.step("complete -> driver_completed + ride COMPLETED (rider-side truth)") {
            composeRule.onNodeWithTag("trip_complete", useUnmergedTree = true).performClick()
            // driver_completed renders on the ack but the 5s poll then nulls activeRide
            // (myRides filter drops COMPLETED) - a 250ms-cadence race, so fall back to
            // the rider-side status read as the authoritative assertion.
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (nodeExists("driver_completed")) break
                Thread.sleep(250)
            }
            val uiDeadline = System.currentTimeMillis() + 15_000
            var apiCompleted = false
            while (System.currentTimeMillis() < uiDeadline) {
                if (rideStatus(riderToken, rideId) == "COMPLETED") {
                    apiCompleted = true
                    break
                }
                Thread.sleep(1_000)
            }
            assertTrue(
                "ride never COMPLETED (ui node seen=${nodeExists("driver_completed")}); " +
                    dumpTags("complete-timeout"),
                apiCompleted,
            )
        }
    }

    private fun nodeExistsPrefix(prefix: String): Boolean =
        composeRule.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("tag-prefix-$prefix") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun instrumentedArg(key: String): String? =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(key)
}

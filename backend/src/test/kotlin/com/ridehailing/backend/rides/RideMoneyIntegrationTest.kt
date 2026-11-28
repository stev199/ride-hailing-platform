package com.ridehailing.backend.rides

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.module
import com.ridehailing.backend.wallet.WalletService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-2 ride money integration (through the PUBLIC API like AdminLiveRoutesTest):
 * WALLET booking (upfront_locked only) -> driver accept HOLDS the fare ->
 * completion CAPTURES with the configured commission -> the ledger conserves
 * value end to end. The wallet lifecycle rides on the AuthGraph's own ledger,
 * so every assertion reads the truth the production wiring uses.
 */
class RideMoneyIntegrationTest {

    private fun withApp(block: suspend (HttpClient, AuthGraph) -> Unit) = testApplication {
        val graph = AuthGraph()
        application { module(testProfile = false, auth = graph) }
        block(client, graph)
    }

    private fun createUserIn(graph: AuthGraph, phone: String, role: Role): User {
        val user = User(
            id = Ids.newId(),
            phone = phone, email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "User ${role.name}", role = role, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(user)
        return user
    }

    private suspend fun HttpClient.getToken(identifier: String, password: String): String {
        val res = post("/v1/auth/login/password") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"identifier":"$identifier","password":"$password"}""")
        }
        val body = res.bodyAsText()
        check(res.status == HttpStatusCode.OK) { "login failed: ${res.status} $body" }
        return Json.parseToJsonElement(body).jsonObject["tokens"]!!
            .jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            header(HttpHeaders.ContentType, "application/json")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.bookRide(token: String, paymentMethod: String): HttpResponse =
        postJson(
            "/v1/rides",
            """{"pickupLat":43.7384,"pickupLon":7.4246,"dropoffLat":43.7543,"dropoffLon":7.4312,
                "city":"monaco","rideType":"standard","paymentMethod":"$paymentMethod"}""",
            token,
        )

    @Test
    fun `wallet ride lifecycle - hold at accept, capture with commission at completion`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33440000001", Role.RIDER)
        val riderToken = client.getToken("33440000001", "password-1")
        val driver = createUserIn(graph, "33440000002", Role.DRIVER)
        val driverToken = client.getToken("33440000002", "password-1")

        // Seed the rider with points (admin credit) and set a 10% commission.
        graph.walletService.adminCredit(rider.id, 10_000, "seed")
        // The wallet config is admin-patchable; drive it through the service to stay honest.
        graph.config.updateWallet("test", graph.config.walletConfig.copy(commissionBps = 1000))

        val booking = client.bookRide(riderToken, "WALLET")
        assertEquals(HttpStatusCode.Created, booking.status, booking.bodyAsText())
        val rideId = booking.json()["id"]!!.jsonPrimitive.content
        // The fare is the SERVER-SIGNED quote (never a hardcoded number).
        val fareMinor = booking.json()["quote"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content.toLong()
        check(fareMinor > 0)

        // Before accept: no hold.
        assertEquals(10_000L, graph.walletService.balances(rider.id)[WalletService.POINTS])
        assertEquals(0L, graph.walletService.held(rider.id))

        // Accept: the fare moves into the hold wallet.
        val accept = client.postJson("/v1/rides/$rideId/accept", "{}", driverToken)
        assertEquals(HttpStatusCode.OK, accept.status, accept.bodyAsText())
        assertEquals(10_000L - fareMinor, graph.walletService.balances(rider.id)[WalletService.POINTS], "seed minus hold")
        assertEquals(fareMinor, graph.walletService.held(rider.id))

        // Complete: capture with 10% commission.
        for (target in listOf("arrive", "start")) {
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/rides/$rideId/$target", "{}", driverToken).status)
        }
        val complete = client.postJson("/v1/rides/$rideId/complete", "{}", driverToken)
        assertEquals(HttpStatusCode.OK, complete.status, complete.bodyAsText())
        val commission = fareMinor * 1_000 / 10_000
        assertEquals(fareMinor - commission, graph.walletService.balances(driver.id)[WalletService.POINTS], "driver gets fare minus 10%")
        assertEquals(commission, graph.walletService.balances(WalletService.PLATFORM_FEE_OWNER)[WalletService.POINTS], "commission")
        assertEquals(0L, graph.walletService.held(rider.id), "hold fully captured")

        // Closed-system conservation: every balance sums to zero.
        val store = graph.ledger as com.ridehailing.backend.ledger.InMemoryLedgerStore
        assertEquals(0L, store.snapshotBalances().values.sum(), "double-entry conservation")
    }

    @Test
    fun `wallet hold failure refuses the accept and keeps the ride on the board`() = withApp { client, graph ->
        createUserIn(graph, "33440000011", Role.RIDER)
        val riderToken = client.getToken("33440000011", "password-1")
        val driver = createUserIn(graph, "33440000012", Role.DRIVER)
        val driverToken = client.getToken("33440000012", "password-1")

        val booking = client.bookRide(riderToken, "WALLET")
        assertEquals(HttpStatusCode.Created, booking.status)
        val rideId = booking.json()["id"]!!.jsonPrimitive.content

        // No admin credit -> the hold cannot succeed.
        val accept = client.postJson("/v1/rides/$rideId/accept", "{}", driverToken)
        assertEquals(HttpStatusCode.Conflict, accept.status, accept.bodyAsText())
        assertEquals("wallet_hold_failed", accept.json()["code"]!!.jsonPrimitive.content)
        // The ride stays REQUESTED (the race window was not burned by an unfunded rider):
        // a duty-on driver still sees it on the available board.
        client.postJson("/v1/driver/duty", """{"online":true,"lat":43.7384,"lon":7.4246}""", driverToken)
        val board = client.get("/v1/driver/rides/available") { header(HttpHeaders.Authorization, "Bearer $driverToken") }
        assertEquals(HttpStatusCode.OK, board.status, board.bodyAsText())
        assertTrue(board.bodyAsText().contains(rideId), "ride still on the available board")
    }

    @Test
    fun `metered quotes refuse WALLET payment - upfront_locked only (honest scope)`() = withApp { client, graph ->
        createUserIn(graph, "33440000021", Role.RIDER)
        val riderToken = client.getToken("33440000021", "password-1")
        // The default quote policy drives the mode; force metered via the fares config.
        val fares = graph.config.fares
        graph.config.updateFares(
            "test",
            fares.copy(quote = fares.quote.copy(mode = "metered_capped")),
        )
        val res = client.bookRide(riderToken, "WALLET")
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
        assertEquals("wallet_requires_upfront_quote", res.json()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `P7-5a admin cancel releases the wallet hold and conserves value`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33440000031", Role.RIDER)
        val riderToken = client.getToken("33440000031", "password-1")
        val driver = createUserIn(graph, "33440000032", Role.DRIVER)
        val driverToken = client.getToken("33440000032", "password-1")
        val ops = createUserIn(graph, "33440000033", Role.ADMIN_OPS)
        val opsToken = client.getToken("33440000033", "password-1")

        graph.walletService.adminCredit(rider.id, 10_000, "seed")
        val booking = client.bookRide(riderToken, "WALLET")
        assertEquals(HttpStatusCode.Created, booking.status)
        val rideId = booking.json()["id"]!!.jsonPrimitive.content
        val fareMinor = booking.json()["quote"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content.toLong()

        // Hold exists after accept.
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/rides/$rideId/accept", "{}", driverToken).status)
        assertEquals(fareMinor, graph.walletService.held(rider.id))

        // Platform cancel: fee-free, hold released back to the rider.
        val cancel = client.postJson("/v1/admin/rides/$rideId/cancel", "{}", opsToken)
        assertEquals(HttpStatusCode.OK, cancel.status, cancel.bodyAsText())
        assertEquals("CANCELLED_BY_ADMIN", cancel.json()["status"]!!.jsonPrimitive.content)
        assertEquals(10_000L, graph.walletService.balances(rider.id)[WalletService.POINTS], "hold released")
        assertEquals(0L, graph.walletService.held(rider.id))

        // Terminal ride cannot be cancelled twice.
        val again = client.postJson("/v1/admin/rides/$rideId/cancel", "{}", opsToken)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("cancel_not_allowed", again.json()["code"]!!.jsonPrimitive.content)

        val store = graph.ledger as com.ridehailing.backend.ledger.InMemoryLedgerStore
        assertEquals(0L, store.snapshotBalances().values.sum(), "double-entry conservation")
        assertTrue(ops.id.isNotEmpty())
    }

    @Test
    fun `P7-5a admin refund reverses the capture exactly`() = withApp { client, graph ->
        val rider = createUserIn(graph, "33440000041", Role.RIDER)
        val riderToken = client.getToken("33440000041", "password-1")
        val driver = createUserIn(graph, "33440000042", Role.DRIVER)
        val driverToken = client.getToken("33440000042", "password-1")
        createUserIn(graph, "33440000043", Role.ADMIN_FINANCE)
        val financeToken = client.getToken("33440000043", "password-1")

        graph.walletService.adminCredit(rider.id, 10_000, "seed")
        graph.config.updateWallet("test", graph.config.walletConfig.copy(commissionBps = 1000))

        val booking = client.bookRide(riderToken, "WALLET")
        val rideId = booking.json()["id"]!!.jsonPrimitive.content
        val fareMinor = booking.json()["quote"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content.toLong()
        check(client.postJson("/v1/rides/$rideId/accept", "{}", driverToken).status == HttpStatusCode.OK)
        for (target in listOf("arrive", "start", "complete")) {
            check(client.postJson("/v1/rides/$rideId/$target", "{}", driverToken).status == HttpStatusCode.OK)
        }
        val commission = fareMinor * 1_000 / 10_000
        check(graph.walletService.balances(driver.id)[WalletService.POINTS] == fareMinor - commission)

        // Refund: rider gets the FULL fare back, driver debited what he got,
        // commission reversed - one atomic transaction.
        val refund = client.postJson("/v1/admin/rides/$rideId/refund", "{}", financeToken)
        assertEquals(HttpStatusCode.OK, refund.status, refund.bodyAsText())
        assertEquals(fareMinor, refund.json()["riderGotMinor"]!!.jsonPrimitive.content.toLong())
        assertEquals(fareMinor - commission, refund.json()["driverDebitedMinor"]!!.jsonPrimitive.content.toLong())
        assertEquals(commission, refund.json()["commissionReversedMinor"]!!.jsonPrimitive.content.toLong())

        assertEquals(10_000L, graph.walletService.balances(rider.id)[WalletService.POINTS], "rider whole again")
        assertEquals(0L, graph.walletService.balances(driver.id)[WalletService.POINTS], "driver debited")
        assertEquals(0L, graph.walletService.balances(WalletService.PLATFORM_FEE_OWNER)[WalletService.POINTS], "commission reversed")

        // CASH rides are not refundable through the ledger.
        val cashBooking = client.bookRide(riderToken, "CASH")
        val cashId = cashBooking.json()["id"]!!.jsonPrimitive.content
        val cashRefund = client.postJson("/v1/admin/rides/$cashId/refund", "{}", financeToken)
        assertEquals(HttpStatusCode.Conflict, cashRefund.status)
        assertEquals("refund_not_allowed", cashRefund.json()["code"]!!.jsonPrimitive.content)

        val store = graph.ledger as com.ridehailing.backend.ledger.InMemoryLedgerStore
        assertEquals(0L, store.snapshotBalances().values.sum(), "double-entry conservation")
    }
}

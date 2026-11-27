package com.ridehailing.backend.verification

import com.ridehailing.backend.AuthGraph
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verification workflow service tests (P6-1): the business rules the routes rely
 * on. The audit payload assertions pin the PII-minimization contract (license/
 * plate values NEVER land in the event store - only ids, counts and reasons).
 */
class VerificationServiceTest {

    private fun graph(): AuthGraph = AuthGraph()

    private fun AuthGraph.verification(): VerificationService = VerificationService(
        store = verificationStore,
        eventRecorder = { type, payload -> eventChain.append(type, payload) },
        clock = { java.time.Instant.ofEpochSecond(1_764_000_000L) },
    )

    private fun driver(graph: AuthGraph, phone: String = "33631000101"): Pair<User, VerificationService> {
        val user = User(
            id = Ids.newId(), phone = phone, email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Driver", role = Role.DRIVER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(user)
        return user to graph.verification()
    }

    private fun fields(
        license: String = "FR-12345678",
        plate: String = "AA-123-BB",
        docs: List<String> = listOf("license_front", "insurance"),
    ) = VerificationService.SubmitFields(license, plate, docs)

    @Test
    fun `driver submits and lands in the queue pending`() {
        val graph = graph()
        val (user, service) = driver(graph)
        val result = service.submit(user, fields())
        assertTrue(result is VerificationService.SubmitResult.Created)
        val submission = (result as VerificationService.SubmitResult.Created).submission
        assertEquals(VerificationStatus.PENDING, submission.status)
        assertNull(submission.reviewedAtEpochSec)
        assertEquals(listOf("license_front", "insurance"), submission.declaredDocs)
        assertEquals(1, service.queue(VerificationStatus.PENDING, 50, 0).size)
    }

    @Test
    fun `rider and admin cannot submit - only drivers`() {
        val graph = graph()
        val service = graph.verification()
        val rider = User(
            id = Ids.newId(), phone = "33631000102", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Rider", role = Role.RIDER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(rider)
        assertEquals(VerificationService.SubmitResult.NotADriver, service.submit(rider, fields()))
        val admin = User(
            id = Ids.newId(), phone = "33631000103", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Admin", role = Role.ADMIN_SUPER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(admin)
        assertEquals(VerificationService.SubmitResult.NotADriver, service.submit(admin, fields()))
    }

    @Test
    fun `second submission while pending or approved is 409-already-pending`() {
        val graph = graph()
        val (user, service) = driver(graph)
        assertTrue(service.submit(user, fields()) is VerificationService.SubmitResult.Created)
        assertEquals(VerificationService.SubmitResult.AlreadyPending, service.submit(user, fields()))
        // After a REJECT the driver may re-review (the re-review loop).
        val latest = service.latestFor(user)!!
        val reviewer = User(
            id = Ids.newId(), phone = "33631000109", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Verifier", role = Role.ADMIN_VERIFIER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(reviewer)
        assertTrue(service.reject(reviewer, latest.id, "plate mismatch") is VerificationService.ReviewResult.Reviewed)
        assertTrue(service.submit(user, fields()) is VerificationService.SubmitResult.Created)
    }

    @Test
    fun `invalid fields are rejected without touching the store`() {
        val graph = graph()
        val (user, service) = driver(graph)
        assertEquals(VerificationService.SubmitResult.Invalid, service.submit(user, fields(license = "  ")))
        assertEquals(VerificationService.SubmitResult.Invalid, service.submit(user, fields(license = "x".repeat(65))))
        assertEquals(VerificationService.SubmitResult.Invalid, service.submit(user, fields(plate = "")))
        assertEquals(VerificationService.SubmitResult.Invalid, service.submit(user, fields(docs = emptyList())))
        assertEquals(VerificationService.SubmitResult.Invalid, service.submit(user, fields(docs = (1..17).map { "d$it" })))
        assertNull(service.latestFor(user), "invalid submissions must not create rows")
    }

    @Test
    fun `review lifecycle - approve, double-review 409, reject requires reason`() {
        val graph = graph()
        val (user, service) = driver(graph)
        val reviewer = User(
            id = Ids.newId(), phone = "33631000109", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Verifier", role = Role.ADMIN_VERIFIER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(reviewer)

        val created = (service.submit(user, fields()) as VerificationService.SubmitResult.Created).submission

        assertEquals(VerificationService.ReviewResult.Invalid, service.reject(reviewer, created.id, "   "))
        assertEquals(VerificationService.ReviewResult.Invalid, service.reject(reviewer, created.id, "x".repeat(513)))
        // Invalid rejections must NOT have consumed the PENDING state.
        assertEquals(VerificationStatus.PENDING, service.byId(created.id)!!.status)

        assertEquals(VerificationService.ReviewResult.NotFound, service.approve(reviewer, "does-not-exist"))
        assertTrue(service.approve(reviewer, created.id) is VerificationService.ReviewResult.Reviewed)
        assertEquals(VerificationStatus.APPROVED, service.byId(created.id)!!.status)
        // APPROVED is terminal for v1: a second review is 409 not_pending.
        assertEquals(VerificationService.ReviewResult.NotPending, service.approve(reviewer, created.id))
        assertEquals(VerificationService.ReviewResult.NotPending, service.reject(reviewer, created.id, "again"))
    }

    @Test
    fun `reject carries the reviewer id, reason and timestamp`() {
        val graph = graph()
        val (user, service) = driver(graph)
        val reviewer = User(
            id = Ids.newId(), phone = "33631000109", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Verifier", role = Role.ADMIN_VERIFIER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(reviewer)
        val created = (service.submit(user, fields()) as VerificationService.SubmitResult.Created).submission
        val reviewed = (service.reject(reviewer, created.id, "license unreadable") as VerificationService.ReviewResult.Reviewed).submission
        assertEquals(VerificationStatus.REJECTED, reviewed.status)
        assertEquals(reviewer.id, reviewed.reviewerId)
        assertEquals("license unreadable", reviewed.rejectReason)
        assertNotNull(reviewed.reviewedAtEpochSec)
        assertEquals(1_764_000_000L, reviewed.reviewedAtEpochSec)
    }

    @Test
    fun `audit trail is PII-minimal - no license or plate values in the event store`() {
        val graph = graph()
        val (user, service) = driver(graph)
        val reviewer = User(
            id = Ids.newId(), phone = "33631000109", email = null,
            passwordHash = graph.passwords.hash("password-1"),
            displayName = "Verifier", role = Role.ADMIN_VERIFIER, status = UserStatus.ACTIVE,
            locale = "en", createdAtEpochSec = 0,
        )
        graph.users.insert(reviewer)
        val created = (service.submit(user, fields()) as VerificationService.SubmitResult.Created).submission
        service.reject(reviewer, created.id, "plate mismatch")

        val events = graph.eventChain.snapshot()
        val verificationEvents = events.filter { it.type.startsWith("verification.") }
        assertEquals(2, verificationEvents.size)
        assertEquals("verification.submitted", verificationEvents[0].type)
        assertEquals("verification.rejected", verificationEvents[1].type)
        val joined = verificationEvents.joinToString(" ") { it.payloadJson }
        assertFalse(joined.contains("FR-12345678"), "license number must never land in the audit chain")
        assertFalse(joined.contains("AA-123-BB"), "vehicle plate must never land in the audit chain")
        assertTrue(joined.contains(created.id))
        assertTrue(joined.contains("plate mismatch"), "the reject reason IS audit-relevant (reviewer words, not PII)")
    }
}

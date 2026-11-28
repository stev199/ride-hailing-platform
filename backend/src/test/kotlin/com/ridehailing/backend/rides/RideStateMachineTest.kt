package com.ridehailing.backend.rides

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P4-2 trip state machine: the FULL transition map is pinned now (dispatch code
 * lands Phase 5 - it must not be able to invent illegal transitions).
 */
class RideStateMachineTest {

    @Test
    fun `requested ride can be accepted cancelled cancelled-by-admin timed-out or expired`() {
        val allowed = RideStatus.REQUESTED.allowed()
        assertEquals(
            setOf(
                RideStatus.ACCEPTED, RideStatus.CANCELLED_BY_RIDER, RideStatus.CANCELLED_BY_ADMIN,
                RideStatus.CANCELLED_TIMEOUT, RideStatus.EXPIRED,
            ),
            allowed,
        )
    }

    @Test
    fun `in-progress ride can complete or be platform-cancelled (P7-5a)`() {
        assertEquals(setOf(RideStatus.COMPLETED, RideStatus.CANCELLED_BY_ADMIN), RideStatus.IN_PROGRESS.allowed())
    }

    @Test
    fun `terminal states have no transitions`() {
        for (s in listOf(
            RideStatus.COMPLETED, RideStatus.CANCELLED_BY_RIDER, RideStatus.CANCELLED_BY_DRIVER,
            RideStatus.CANCELLED_BY_ADMIN, RideStatus.CANCELLED_TIMEOUT, RideStatus.EXPIRED,
        )) {
            assertTrue(s.allowed().isEmpty(), "$s must be terminal")
            assertTrue(s.terminal)
        }
    }

    @Test
    fun `illegal transitions throw`() {
        for (illegal in listOf(
            RideStatus.REQUESTED to RideStatus.COMPLETED,
            RideStatus.REQUESTED to RideStatus.IN_PROGRESS,
            RideStatus.EXPIRED to RideStatus.ACCEPTED,
            RideStatus.COMPLETED to RideStatus.CANCELLED_BY_RIDER,
        )) {
            try {
                illegal.first.requireTransitionTo(illegal.second)
                fail("expected throw for ${illegal.first} -> ${illegal.second}")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("illegal transition"))
            }
        }
    }

    @Test
    fun `legal transitions pass the guard`() {
        RideStatus.REQUESTED.requireTransitionTo(RideStatus.EXPIRED)
        RideStatus.REQUESTED.requireTransitionTo(RideStatus.ACCEPTED)
        RideStatus.ACCEPTED.requireTransitionTo(RideStatus.ARRIVED)
        RideStatus.ARRIVED.requireTransitionTo(RideStatus.IN_PROGRESS)
        RideStatus.IN_PROGRESS.requireTransitionTo(RideStatus.COMPLETED)
    }
}

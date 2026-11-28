package com.ridehailing.backend.rides

import kotlinx.serialization.Serializable

/**
 * P4-2 trip state machine (spec §45/§99: every trip state change is audited;
 * the machine is enforced IN CODE here and pinned by tests - the DB stores the
 * status string only).
 *
 * Phase 4 active paths: REQUESTED (booking) -> CANCELLED_BY_RIDER | EXPIRED.
 * ACCEPTED/ARRIVED/IN_PROGRESS/COMPLETED arrive with the driver app (Phase 5);
 * the full transition map is defined NOW so dispatch code cannot invent
 * illegal transitions later.
 */
enum class RideStatus {
    REQUESTED,
    ACCEPTED,
    ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED_BY_RIDER,
    CANCELLED_BY_DRIVER,
    CANCELLED_BY_ADMIN,
    CANCELLED_TIMEOUT,
    EXPIRED,
    ;

    /** Legal transitions from this state (empty = terminal). Function, not a property: enum-entry initializers cannot reference sibling entries. */
    fun allowed(): Set<RideStatus> = when (this) {
        // P7-5a: the platform can cancel ANY non-terminal ride (admin abort incl. an
        // in-progress trip) - fee-free, wallet hold released by the service layer.
        REQUESTED -> setOf(ACCEPTED, CANCELLED_BY_RIDER, CANCELLED_BY_ADMIN, CANCELLED_TIMEOUT, EXPIRED)
        ACCEPTED -> setOf(ARRIVED, CANCELLED_BY_RIDER, CANCELLED_BY_DRIVER, CANCELLED_BY_ADMIN)
        ARRIVED -> setOf(IN_PROGRESS, CANCELLED_BY_RIDER, CANCELLED_BY_DRIVER, CANCELLED_BY_ADMIN)
        IN_PROGRESS -> setOf(COMPLETED, CANCELLED_BY_ADMIN)
        COMPLETED, CANCELLED_BY_RIDER, CANCELLED_BY_DRIVER, CANCELLED_BY_ADMIN, CANCELLED_TIMEOUT, EXPIRED -> emptySet()
    }

    val terminal: Boolean get() = allowed().isEmpty()
}

/** Validate one transition; throws on illegal moves (service maps to 409). */
fun RideStatus.requireTransitionTo(next: RideStatus) {
    require(next in allowed()) { "illegal transition $name -> $next.name" }
}

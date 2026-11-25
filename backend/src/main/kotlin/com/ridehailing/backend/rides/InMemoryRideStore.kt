package com.ridehailing.backend.rides

import com.ridehailing.backend.auth.Ids

/** P4-2 in-memory RideStore (default graph; PG store joins on CI like the P1-3 stores). */
class InMemoryRideStore : RideStore {
    private val rides = LinkedHashMap<String, Ride>()

    /** Insertion sequence: tie-break for rides created in the same second (PG orders by created_at DESC, id). */
    private val seq = HashMap<String, Long>()
    private var nextSeq = 0L
    private val lock = Any()

    override fun insert(ride: Ride): Ride = synchronized(lock) {
        rides[ride.id] = ride // pickup/dropoff names ride on the object (stop rows are PG-normalization)
        seq[ride.id] = nextSeq++
        ride
    }

    override fun byId(id: String): Ride? = synchronized(lock) { rides[id] }

    override fun updateStatus(id: String, status: RideStatus, nowEpochSec: Long, cancelFeeMinor: Long): Ride? = synchronized(lock) {
        val current = rides[id] ?: return null
        val next = current.copy(
            status = status,
            cancelFeeMinor = if (cancelFeeMinor > 0) cancelFeeMinor else current.cancelFeeMinor,
            updatedAtEpochSec = nowEpochSec,
        )
        rides[id] = next
        next
    }

    override fun forRider(riderId: String, limit: Int, offset: Int): List<Ride> = synchronized(lock) {
        rides.values
            .filter { it.riderId == riderId }
            .sortedWith(compareByDescending<Ride> { it.createdAtEpochSec }.thenByDescending { seq[it.id] ?: 0L })
            .drop(offset)
            .take(limit)
    }

    override fun available(limit: Int): List<Ride> = synchronized(lock) {
        rides.values
            .filter { it.status == RideStatus.REQUESTED }
            .sortedWith(compareBy<Ride> { it.createdAtEpochSec }.thenBy { seq[it.id] ?: 0L })
            .take(limit)
    }

    override fun claimForDriver(id: String, driverId: String, nowEpochSec: Long): Ride? = synchronized(lock) {
        val current = rides[id] ?: return null
        // Single-winner under the store lock: only a still-REQUESTED ride is claimable.
        if (current.status != RideStatus.REQUESTED) return null
        val claimed = current.copy(
            status = RideStatus.ACCEPTED,
            driverId = driverId,
            updatedAtEpochSec = nowEpochSec,
        )
        rides[id] = claimed
        claimed
    }

    override fun forDriver(driverId: String, limit: Int, offset: Int): List<Ride> = synchronized(lock) {
        rides.values
            .filter { it.driverId == driverId }
            .sortedWith(compareByDescending<Ride> { it.createdAtEpochSec }.thenByDescending { seq[it.id] ?: 0L })
            .drop(offset)
            .take(limit)
    }

    fun newId(): String = Ids.newId()
}

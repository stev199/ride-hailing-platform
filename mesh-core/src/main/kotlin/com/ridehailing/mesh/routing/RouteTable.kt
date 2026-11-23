package com.ridehailing.mesh.routing

/**
 * Route table (Stage 2): best path + one node-disjoint-as-possible backup per
 * destination, with expiry (default 60 s) refreshed by traffic and PING (spec).
 * Thread-safe.
 */
class RouteTable(
    private val expiryMs: Long = DEFAULT_EXPIRY_MS,
    private val clock: MeshClock,
) {
    data class RouteEntry(
        val destination: NodeAddress,
        val bestPath: CandidatePath,
        val backupPath: CandidatePath?,
        val cost: Double,
        var expiresAtMs: Long,
    )

    private val routes = HashMap<NodeAddress, RouteEntry>()

    /** Inserts/updates a route; selection between candidates is done by the caller. */
    @Synchronized
    fun update(
        destination: NodeAddress,
        bestPath: CandidatePath,
        backupPath: CandidatePath?,
        cost: Double,
    ) {
        val now = clock.nowMs()
        routes[destination] = RouteEntry(destination, bestPath, backupPath, cost, now + expiryMs)
    }

    /** Returns the live entry for [destination] and refreshes its expiry (traffic refresh). */
    @Synchronized
    fun lookup(destination: NodeAddress): RouteEntry? {
        val entry = routes[destination] ?: return null
        if (entry.expiresAtMs <= clock.nowMs()) {
            routes.remove(destination)
            return null
        }
        entry.expiresAtMs = clock.nowMs() + expiryMs
        return entry
    }

    @Synchronized
    fun evictExpired(): Int {
        val now = clock.nowMs()
        val before = routes.size
        routes.entries.removeIf { it.value.expiresAtMs <= now }
        return before - routes.size
    }

    @Synchronized
    fun size(): Int = routes.size

    @Synchronized
    fun clear() = routes.clear()

    companion object {
        const val DEFAULT_EXPIRY_MS = 60L * 1000
    }
}

package com.ridehailing.mesh.routing

/**
 * Store-and-forward queue (Stage 2 fallback): holds envelopes with an expiry, a
 * preferred next hop and a delivery receipt. Entries expire with the envelope TTL
 * and are reported (GATE 3 scenario 10: "expired ones dropped and reported").
 */
class StoreAndForward(
    private val clock: MeshClock,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    data class Queued(
        val id: String,
        val envelope: com.ridehailing.mesh.protocol.Envelope,
        val nextHop: NodeAddress?,
        val expiresAtMs: Long,
        val queuedAtMs: Long,
    )

    /** Receipt callback: delivered=true on explicit ack, false on expiry/drop. */
    fun interface Receipt {
        fun onOutcome(queued: Queued, delivered: Boolean)
    }

    private val queue = ArrayDeque<Queued>()
    private val receipts = HashMap<String, Receipt>()

    @Synchronized
    fun enqueue(envelope: com.ridehailing.mesh.protocol.Envelope, nextHop: NodeAddress?, expiresAtMs: Long, receipt: Receipt?): String {
        val id = nextQueueId()
        val queued = Queued(id, envelope, nextHop, expiresAtMs, clock.nowMs())
        queue.addLast(queued)
        receipt?.let { receipts[id] = it }
        while (queue.size > capacity) {
            val evicted = queue.removeFirst()
            receipts.remove(evicted.id)?.onOutcome(evicted, delivered = false)
        }
        return id
    }

    /** Entries whose TTL is still live, oldest first. */
    @Synchronized
    fun drainReady(): List<Queued> {
        val now = clock.nowMs()
        val ready = mutableListOf<Queued>()
        val expired = mutableListOf<Queued>()
        for (item in queue) {
            if (item.expiresAtMs <= now) expired.add(item) else ready.add(item)
        }
        queue.removeAll { it.expiresAtMs <= now }
        expired.forEach { receipts.remove(it.id)?.onOutcome(it, delivered = false) }
        return ready
    }

    /** Acknowledge a delivered message; fires its receipt. */
    @Synchronized
    fun acknowledge(id: String): Boolean {
        val removed = queue.filter { it.id == id }
        queue.removeAll { it.id == id }
        val receipt = receipts.remove(id)
        receipt?.let { r ->
            removed.firstOrNull()?.let { r.onOutcome(it, delivered = true) }
        }
        return removed.isNotEmpty()
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        val all = queue.toList()
        queue.clear()
        all.forEach { receipts.remove(it.id)?.onOutcome(it, delivered = false) }
    }

    private var counter = 0L
    private fun nextQueueId(): String = "sq-${++counter}"

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}

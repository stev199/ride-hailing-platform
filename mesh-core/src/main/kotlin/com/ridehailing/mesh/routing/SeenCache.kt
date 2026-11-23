package com.ridehailing.mesh.routing

/**
 * Replay/seen-cache (Stage 2 forward leg): dedups (originId, msgId) pairs with a TTL
 * equal to the envelope's max TTL, bounded by an LRU cap (GATE 3 scenario 4: "no
 * packet circulates more than once per node"; Stage 1 replay protection). Thread-safe.
 */
class SeenCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    data class Key(val originId: ByteArray, val msgId: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Key && originId.contentEquals(other.originId) && msgId.contentEquals(other.msgId)

        override fun hashCode(): Int = 31 * originId.contentHashCode() + msgId.contentHashCode()
    }

    private data class Entry(val key: Key, val expiresAtMs: Long)

    private val order = ArrayDeque<Entry>() // front = oldest
    private val set = HashMap<Key, Long>() // key -> expiresAtMs

    /**
     * Records [key] if unseen (and unexpired). Returns true when the key was NEW
     * (first sighting -> forward), false when a duplicate (or expired re-insert).
     */
    @Synchronized
    fun see(key: Key, nowMs: Long): Boolean {
        val existing = set[key]
        if (existing != null && existing > nowMs) return false
        set.remove(key)
        order.removeAll { it.key == key }
        set[key] = nowMs + ttlMs
        order.addLast(Entry(key, nowMs + ttlMs))
        while (order.size > capacity) {
            val evicted = order.removeFirst()
            set.remove(evicted.key)
        }
        return true
    }

    /** True when [key] is present and not expired. */
    @Synchronized
    fun seen(key: Key, nowMs: Long): Boolean {
        val expires = set[key] ?: return false
        return expires > nowMs
    }

    @Synchronized
    fun size(): Int = order.size

    companion object {
        const val DEFAULT_CAPACITY = 4096
        const val DEFAULT_TTL_MS = 120L * 1000 // matches max envelope TTL budget
    }
}

/**
 * Per-origin rate limiter (Stage 2: "per-origin rate limits"; GATE 3 scenario 12:
 * single-origin spam dropped, honest delivery drop <= 5%). Sliding window, bounded
 * memory per origin (timestamps pruned on access; idle origins evicted at cap).
 */
class OriginRateLimiter(
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxTrackedOrigins: Int = 1024,
) {
    // ByteArray keys would use identity equality; key by Base64 content instead.
    private val buckets = HashMap<String, MutableList<Long>>()

    private fun keyOf(originId: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(originId)

    /** Returns true when [originId] may send now; false when over budget. */
    @Synchronized
    fun tryAcquire(originId: ByteArray, nowMs: Long): Boolean {
        val key = keyOf(originId)
        val list = buckets.getOrPut(key) { mutableListOf() }
        val windowStart = nowMs - windowMs
        list.removeAll { it <= windowStart }
        if (list.size >= maxPerWindow) return false
        list.add(nowMs)
        if (buckets.size > maxTrackedOrigins) {
            // Evict oldest-inserted origins until back under the cap.
            val it = buckets.entries.iterator()
            while (it.hasNext() && buckets.size > maxTrackedOrigins) {
                it.next()
                it.remove()
            }
        }
        return true
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 10
        const val DEFAULT_WINDOW_MS = 10L * 1000
    }
}

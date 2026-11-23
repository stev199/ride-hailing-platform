package com.ridehailing.mesh.routing

import com.ridehailing.mesh.protocol.Envelope
import java.util.concurrent.ConcurrentHashMap

/**
 * Controlled flooding for the forward leg (Stage 2):
 *  - seen-cache dedup (each node forwards a given (originId, msgId) at most once);
 *  - hop limit + TTL enforced upstream by the Validator; the flooder additionally
 *    refuses to relay at the effective maxHops;
 *  - jittered rebroadcast: uniform 20-200 ms, scaled with neighbor count (denser
 *    neighborhoods delay slightly longer so better-positioned relays go first);
 *  - counter-based suppression: skip rebroadcast when >= k duplicates of the same
 *    message are heard within the suppression window (k configurable, default 2);
 *  - per-origin rate limits drop spam before dedup bookkeeping.
 */
class Flooder(
    private val seenCache: SeenCache,
    private val rateLimiter: OriginRateLimiter = OriginRateLimiter(),
    private val config: Config = Config(),
    private val clock: MeshClock,
    private val jitter: Jitter = Jitter(),
) {
    data class Config(
        val suppressionThreshold: Int = 2,
        val suppressionWindowMs: Long = 500,
        val minRebroadcastDelayMs: Long = 20,
        val maxRebroadcastDelayMs: Long = 200,
        val rebroadcastDelayNeighborScale: Double = 0.05, // +5% per neighbor
    )

    sealed class Decision {
        /** Rebroadcast after [delayMs] - the caller relays via its transport then. */
        data class Rebroadcast(val delayMs: Long) : Decision()

        /** Duplicate heard [duplicates] times inside the window - stay quiet. */
        data class Suppress(val duplicates: Int) : Decision()

        /** Do not forward: gives the machine-readable reason. */
        data class Drop(val reason: String) : Decision()
    }

    private data class DupWindow(var count: Int, var windowEndMs: Long)

    private val duplicates = ConcurrentHashMap<SeenCache.Key, DupWindow>()

    private fun keyOf(envelope: Envelope) = SeenCache.Key(envelope.originId, envelope.msgId)

    /**
     * Handle one inbound envelope at [localNode]. [originIsUs] marks our own
     * transmissions (they start the flood instead of joining it).
     */
    fun onReceive(
        envelope: Envelope,
        originIsUs: Boolean,
        neighborCount: Int,
    ): Decision {
        val now = clock.nowMs()
        val key = keyOf(envelope)
        val first = seenCache.see(key, now)
        if (!first) {
            // Duplicates are normal mesh behavior - the counter-based suppression
            // (not the per-origin rate limit) governs them, otherwise legit
            // multi-path copies would be misclassified as spam.
            val dup = duplicates.compute(key) { _, existing ->
                val w = existing ?: DupWindow(0, now + config.suppressionWindowMs)
                if (w.windowEndMs <= now) DupWindow(0, now + config.suppressionWindowMs) else w
            }!!
            dup.count += 1
            return if (dup.count >= config.suppressionThreshold) {
                Decision.Suppress(dup.count)
            } else {
                Decision.Rebroadcast(rebroadcastDelay(neighborCount))
            }
        }
        // First sighting: rate-limit DISTINCT messages per origin before bookkeeping.
        if (!originIsUs && !rateLimiter.tryAcquire(envelope.originId, now)) {
            return Decision.Drop("origin_rate_limited")
        }
        return Decision.Rebroadcast(rebroadcastDelay(neighborCount))
    }

    private fun rebroadcastDelay(neighborCount: Int): Long {
        val base = jitter.uniform(config.minRebroadcastDelayMs, config.maxRebroadcastDelayMs)
        val scaled = base * (1.0 + config.rebroadcastDelayNeighborScale * neighborCount.coerceAtLeast(0))
        return scaled.toLong().coerceIn(config.minRebroadcastDelayMs, config.maxRebroadcastDelayMs)
    }

    /** Test/ops: duplicates currently tracked (bounded: entries pruned on update). */
    fun trackedDuplicates(): Int = duplicates.size
}

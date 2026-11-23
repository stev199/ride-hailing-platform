package com.ridehailing.mesh.sim

import com.ridehailing.mesh.routing.CancelHandle
import com.ridehailing.mesh.routing.DelayScheduler
import com.ridehailing.mesh.routing.MeshClock
import java.util.PriorityQueue
import java.util.Random

/**
 * Stage 3 deterministic discrete-event engine (Part C: "deterministic seeds").
 *
 * Pure virtual time: no threads, no sleeps. Events are (timeMs, seq) ordered; the
 * simulation runs by popping events until the queue is empty or the horizon is hit.
 *
 * Inline execution: the source-routed return leg runs synchronously inside a single
 * event handler (the transport advances the virtual clock between attempts to model
 * backoff/latency). While inline, other pending events are NOT interleaved - they
 * simply fire later than scheduled but never earlier (time stays monotonic). This is
 * a documented simplification: it keeps the per-hop HOP_ACK retry sequence atomic
 * without threads, which is exactly the determinism GATE 3 needs.
 */
class SimEngine(val seed: Long) {

    /** Virtual clock shared by every node stack (MeshClock impl). */
    val clock = VirtualClock()

    /** Deterministic RNG for all loss/latency sampling (java.util.Random is JVM-specified). */
    val random = Random(seed)

    inner class VirtualClock : MeshClock {
        internal var now: Long = 0L
        override fun nowMs(): Long = now
    }

    private class Event(val atMs: Long, val seq: Long, val action: () -> Unit) {
        fun key(): Long = atMs * SEQ_SPACE + seq
        companion object { const val SEQ_SPACE = 1_000_000L }
    }

    private val queue = PriorityQueue<Event>(compareBy { it.key() })
    private var seq = 0L
    @PublishedApi
    internal var inlineDepth = 0
    var processedEvents = 0L
        private set

    /** Posts [action] at absolute virtual time [atMs] (late-fired events never run early). */
    fun post(atMs: Long, action: () -> Unit): CancelHandle {
        val e = Event(atMs, seq++, action)
        queue.add(e)
        return CancelHandle { queue.remove(e) }
    }

    fun postIn(delayMs: Long, action: () -> Unit): CancelHandle = post(clock.now + delayMs.coerceAtLeast(0), action)

    /** Virtual-time DelayScheduler for the routing primitives. */
    val scheduler: DelayScheduler = object : DelayScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit): CancelHandle = postIn(delayMs, action)
    }

    /**
     * Runs [block] inline: the block may advance the clock (via [advance]) without
     * other events interleaving. Nested inline blocks are allowed.
     */
    inline fun runInline(block: () -> Unit) {
        inlineDepth++
        try { block() } finally { inlineDepth-- }
    }

    /** Advances the virtual clock immediately (only meaningful inside runInline). */
    fun advance(deltaMs: Long) {
        check(inlineDepth > 0) { "advance() outside inline execution would break event ordering" }
        clock.now += deltaMs.coerceAtLeast(0)
    }

    /** Runs all events until the queue is empty or [horizonMs] is reached. */
    fun runUntil(horizonMs: Long) {
        while (queue.isNotEmpty()) {
            val e = queue.peek()
            if (e.atMs > horizonMs) break
            val ev = queue.poll()
            if (ev.atMs > clock.now) clock.now = ev.atMs // never run early; inline advances may have passed it
            processedEvents++
            ev.action()
        }
        if (clock.now < horizonMs) clock.now = horizonMs
    }

    fun pendingEvents(): Int = queue.size
}

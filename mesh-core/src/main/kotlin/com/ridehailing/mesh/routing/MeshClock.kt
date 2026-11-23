package com.ridehailing.mesh.routing

import java.security.SecureRandom

/**
 * Stage 2 routing primitives. Pure Kotlin/JVM, zero Android imports (Part C module
 * layout). Everything is deterministic via injected [MeshClock]/[DelayScheduler] so
 * the Stage 3 simulator and CI can drive tests with virtual time.
 */

/** Injectable clock (ms since an arbitrary epoch). */
fun interface MeshClock {
    fun nowMs(): Long
}

/**
 * Injectable delay scheduler. Implementations MUST be safe to schedule from any
 * thread and MAY cancel pending work. The simulator uses a manual virtual-time
 * implementation; production bridges to coroutines/threads.
 */
interface DelayScheduler {
    /** Schedules [action] after [delayMs]; returns a handle for cancellation. */
    fun schedule(delayMs: Long, action: () -> Unit): CancelHandle
}

fun interface CancelHandle {
    fun cancel()
}

/** Logical node address (stable within one routing epoch; beacons rotate wire IDs). */
@JvmInline
value class NodeAddress(val value: String) {
    /** 4-byte hashed hop id for breadcrumbs (FNV-1a 32, stable, testable). */
    fun hopIdHash(): ByteArray {
        var hash = 0x811c9dc5u.toInt()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xff)
            hash *= 0x01000193
        }
        val out = ByteArray(4)
        for (i in 0..3) out[i] = ((hash ushr (8 * i)) and 0xff).toByte()
        return out
    }
}

/** Default real-time clock/scheduler pair for production use. */
object RealTime {
    val clock = MeshClock { System.currentTimeMillis() }
    val scheduler: DelayScheduler = object : DelayScheduler {
        private val executor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
        override fun schedule(delayMs: Long, action: () -> Unit): CancelHandle {
            val f = executor.schedule(action, delayMs.coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
            return CancelHandle { f.cancel(false) }
        }
    }
}

/** Shared deterministic RNG for jittered delays (seedable for tests/simulation). */
class Jitter(seed: Long = System.nanoTime()) {
    private val random = SecureRandom() // production path uses OS entropy
    private val seeded = java.util.Random(seed)
    private val useSeeded = seed != Long.MIN_VALUE

    /** Uniform in [minMs, maxMs]. */
    fun uniform(minMs: Long, maxMs: Long): Long {
        val r = if (useSeeded) seeded else random
        val range = (maxMs - minMs).coerceAtLeast(0) + 1
        return minMs + (r.nextLong().let { if (it < 0) -(it % range) else it % range })
    }
}

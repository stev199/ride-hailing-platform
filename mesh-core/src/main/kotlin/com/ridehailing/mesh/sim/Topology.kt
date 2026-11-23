package com.ridehailing.mesh.sim

import com.ridehailing.mesh.routing.LinkMetrics
import com.ridehailing.mesh.routing.NodeAddress
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Stage 3 topology model (Part C): arbitrary graphs with per-link, per-direction
 * loss/latency/jitter/bandwidth and MTU limits. Static builders cover the mandatory
 * scenarios (line, star, grid, ring, random); mobility recomputes links from radio
 * range every tick (random waypoint + scripted paths, scenario 9).
 */

/** Node role (app-level behavior). */
enum class SimRole { RIDER, DRIVER, RELAY, GATEWAY }

data class Position(val x: Double, val y: Double) {
    fun distanceTo(other: Position): Double = sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y))
}

/**
 * Per-direction link profile. [lossFwd] is the i.i.d. loss probability per
 * routing-layer transmission attempt sender->receiver; [lossRev] applies to the
 * receiver->sender direction. Latency is one-way with uniform jitter.
 */
data class LinkProfile(
    val lossFwd: Double = 0.0,
    val lossRev: Double = 0.0,
    val latencyMs: Double = 10.0,
    val jitterMs: Double = 3.0,
    val bandwidthBps: Long = 115_200, // BLE-ish usable rate
    val mtuBytes: Int = 185,
)

/**
 * A link carries an optional shared link key (hop MAC, GATE 3 scenario 12) and the
 * time it first appeared (instability penalty input; static links are back-dated so
 * they are "stable" per the >60 s rule).
 */
class SimLink(
    val a: NodeAddress,
    val b: NodeAddress,
    val profile: LinkProfile,
    val linkKey: ByteArray? = null,
    val firstSeenMs: Long = -120_000L, // back-dated: links older than 60 s are stable
) {
    fun other(end: NodeAddress): NodeAddress = if (end == a) b else a
    /** Forward = a->b when [from]==a, else b->a. */
    fun lossFrom(from: NodeAddress): Double = if (from == a) profile.lossFwd else profile.lossRev
    fun latencyMs(from: NodeAddress, rng: java.util.Random): Double {
        val jitter = if (profile.jitterMs > 0.0) (rng.nextDouble() * 2.0 - 1.0) * profile.jitterMs else 0.0
        return (profile.latencyMs + jitter).coerceAtLeast(0.5)
    }
    fun frameMs(bytes: Int): Double = bytes * 8.0 / profile.bandwidthBps * 1000.0
}

/** Static snapshot of one hop's cost inputs (mirrors what a node measures on a live link). */
data class LinkSnapshot(
    val from: NodeAddress,
    val to: NodeAddress,
    val etx: Double,
    val latencyMs: Double,
    val batteryPenalty: Double,
    val loadPenalty: Double,
    val instabilityPenalty: Double,
    val revoked: Boolean = false,
    val link: SimLink? = null,
)

/**
 * RSSI -> distance mapping used by mobility (Part C: RSSI feeds the initial ETX):
 * rssi = -40 - 20*log10(d) clamped to [-100, -30].
 */
fun rssiForDistance(distanceM: Double): Int {
    val rssi = -40 - 20 * log10(distanceM.coerceAtLeast(1.0))
    return rssi.coerceIn(-100.0, -30.0).toInt()
}

/** Initial ETX from RSSI using the Stage 2 mapping (documented in LinkMetrics). */
fun initialEtx(rssiDbm: Int): Double = LinkMetrics().initialEtxFromRssi(rssiDbm)

/** Builds the cost-model snapshot for a hop over [link] in direction [from]->[to]. */
fun snapshotFor(
    link: SimLink,
    from: NodeAddress,
    to: NodeAddress,
    toBattery: Int,
    toCharging: Boolean,
    toQueueLen: Int,
    toQueueCap: Int,
    nowMs: Long,
    revoked: Boolean = false,
): LinkSnapshot {
    val metrics = LinkMetrics()
    val loss = link.lossFrom(from)
    val revLoss = link.lossFrom(to)
    val state = LinkMetrics.EtxState(
        forwardDelivery = (1.0 - loss).coerceIn(0.01, 1.0),
        reverseDelivery = (1.0 - revLoss).coerceIn(0.01, 1.0),
    )
    val age = nowMs - link.firstSeenMs
    // RSSI variance is only meaningful under mobility; static sim keeps 0.
    val instability = metrics.instabilityPenalty(age.coerceAtLeast(0), rssiVarianceDb = 0.0)
    return LinkSnapshot(
        from = from, to = to,
        etx = metrics.etx(state),
        latencyMs = link.profile.latencyMs,
        batteryPenalty = metrics.batteryPenalty(toBattery, toCharging),
        loadPenalty = metrics.loadPenalty(toQueueLen, toQueueCap),
        instabilityPenalty = instability,
        revoked = revoked,
        link = link,
    )
}

// ---------------- topology builders ----------------

object Topologies {

    /** Straight line: n0-n1-...-n{n-1}, optional per-link profile override. */
    fun line(world: SimWorld, names: List<String>, spacing: Double = 10.0, profile: (Int) -> LinkProfile = { LinkProfile() }) {
        require(names.size >= 2)
        for (i in 0 until names.size - 1) {
            world.addLink(names[i], names[i + 1], profile(i))
        }
    }

    /** Star: center connected to every leaf. */
    fun star(world: SimWorld, center: String, leaves: List<String>, profile: LinkProfile = LinkProfile()) {
        leaves.forEach { world.addLink(center, it, profile) }
    }

    /** Ring: n0-n1-...-n{n-1}-n0 (scenario 4: loops). */
    fun ring(world: SimWorld, names: List<String>, profile: LinkProfile = LinkProfile()) {
        for (i in 0 until names.size) {
            world.addLink(names[i], names[(i + 1) % names.size], profile)
        }
    }

    /** Grid (cols x rows) with orthogonal neighbors; nodes named g<r>_<c>. */
    fun grid(world: SimWorld, cols: Int, rows: Int, spacing: Double = 20.0, profile: LinkProfile = LinkProfile()) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                world.ensureNode("g${r}_$c", SimRole.RELAY, Position(c * spacing.toDouble(), r * spacing.toDouble()))
                if (c > 0) world.addLink("g${r}_${c - 1}", "g${r}_$c", profile)
                if (r > 0) world.addLink("g${r - 1}_$c", "g${r}_$c", profile)
            }
        }
    }
}

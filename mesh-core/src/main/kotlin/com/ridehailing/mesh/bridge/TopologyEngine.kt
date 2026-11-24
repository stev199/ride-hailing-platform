package com.ridehailing.mesh.bridge

import com.ridehailing.mesh.sim.Position
import java.util.TreeMap
import kotlin.math.min

/**
 * Stage 4 prep (P1B-5): the deterministic topology engine the host sim-bridge uses to
 * decide, per transmission, which connected app instances hear it.
 *
 * Same radio model contract as the Stage 3 simulator (range in meters, i.i.d. loss per
 * transmission attempt, scripted mobility), but STANDALONE: no crypto, no codec, no app
 * logic - the engine only answers "who is in range of whom at time t" and rolls the
 * per-transmission loss dice deterministically from [seed], so a GATE 4 run can be
 * reproduced from (seed, mobility script, message schedule).
 *
 * Mobility: per node a waypoint track [(tSec, x, y)]; position is linearly interpolated
 * between waypoints and clamped to the last waypoint. A single-waypoint node is static.
 */
class TopologyEngine(
    val seed: Long,
    val rangeMeters: Double = 35.0,
    /** i.i.d. loss probability per transmission attempt (sender->receiver). */
    val lossProbability: Double = 0.0,
) {
    private val tracks = TreeMap<String, List<Waypoint>>()
    private val rng = java.util.Random(seed)

    data class Waypoint(val tSec: Double, val x: Double, val y: Double)

    fun addStatic(nodeId: String, position: Position) {
        tracks[nodeId] = listOf(Waypoint(0.0, position.x, position.y))
    }

    /** [points] must be sorted by tSec ascending; timestamps before the first waypoint clamp. */
    fun addTrack(nodeId: String, points: List<Waypoint>) {
        require(points.isNotEmpty()) { "empty track for $nodeId" }
        tracks[nodeId] = points
    }

    fun positionOf(nodeId: String, atSec: Double): Position? {
        val track = tracks[nodeId] ?: return null
        if (atSec <= track.first().tSec) return Position(track.first().x, track.first().y)
        if (atSec >= track.last().tSec) return Position(track.last().x, track.last().y)
        for (i in 0 until track.size - 1) {
            val a = track[i]
            val b = track[i + 1]
            if (atSec >= a.tSec && atSec <= b.tSec) {
                val span = (b.tSec - a.tSec).takeIf { it > 0 } ?: return Position(b.x, b.y)
                val f = (atSec - a.tSec) / span
                return Position(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
        }
        return Position(track.last().x, track.last().y)
    }

    /** Nodes currently registered AND positioned, other than [exclude], within [rangeMeters]. */
    fun neighborsOf(exclude: String, atSec: Double, known: Set<String>): Set<String> {
        val from = positionOf(exclude, atSec) ?: return emptySet()
        return known.filter { it != exclude }
            .mapNotNull { id -> positionOf(id, atSec)?.let { id to it } }
            .filter { (_, p) -> from.distanceTo(p) <= rangeMeters }
            .map { it.first }
            .toSet()
    }

    /**
     * One transmission attempt from -> to at [atSec]. Deterministic: consumes the shared
     * rng ONLY when the pair is in range (range checks stay pure so neighbor sets are
     * stable regardless of loss rolls).
     */
    fun transmissionDelivers(from: String, to: String, atSec: Double): Boolean {
        val a = positionOf(from, atSec) ?: return false
        val b = positionOf(to, atSec) ?: return false
        if (a.distanceTo(b) > rangeMeters) return false
        if (lossProbability <= 0.0) return true
        return rng.nextDouble() >= lossProbability
    }

    fun knownNodes(): Set<String> = tracks.keys.toSet()

    /** Snapshot for the evidence log: every pair distance at [atSec]. */
    fun distancesAt(atSec: Double): Map<Pair<String, String>, Double> {
        val ids = tracks.keys.toList()
        val out = LinkedHashMap<Pair<String, String>, Double>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = positionOf(ids[i], atSec)
            val b = positionOf(ids[j], atSec)
            if (a != null && b != null) out[Pair(ids[i], ids[j])] = min(a.distanceTo(b), Double.MAX_VALUE)
        }
        return out
    }
}

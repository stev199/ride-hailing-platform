package com.ridehailing.mesh.sim

import kotlin.random.Random

/**
 * Stage 3 mobility (Part C): random waypoint + scripted paths, churn and battery
 * drain. Position updates run on a fixed tick; radio links are rebuilt from range
 * every tick (SimWorld.rebuildRadioLinks).
 */
class RandomWaypointMobility(
    private val world: SimWorld,
    private val areaWidthM: Double,
    private val areaHeightM: Double,
    private val rangeM: Double,
    private val speedMPs: Double,
    seed: Long,
    private val tickMs: Long = 1_000,
) {
    private val rng = Random(seed)

    private class Waypoint(var targetX: Double, var targetY: Double)

    private val waypoints = HashMap<String, Waypoint>()

    init {
        for (node in world.allNodes()) {
            waypoints[node.name] = newTarget()
        }
    }

    private fun newTarget(): Waypoint = Waypoint(rng.nextDouble() * areaWidthM, rng.nextDouble() * areaHeightM)

    /** Starts ticking; each tick moves nodes one step toward their waypoint and relinks. */
    fun start() {
        val engine = world.engine
        fun tick() {
            for (node in world.allNodes()) {
                if (!node.up) continue
                val wp = waypoints.getValue(node.name)
                val pos = node.position
                val dx = wp.targetX - pos.x
                val dy = wp.targetY - pos.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val step = speedMPs * tickMs / 1000.0
                if (dist <= step) {
                    node.position = Position(wp.targetX, wp.targetY)
                    val nt = newTarget()
                    wp.targetX = nt.targetX
                    wp.targetY = nt.targetY
                } else {
                    node.position = Position(pos.x + dx / dist * step, pos.y + dy / dist * step)
                }
            }
            world.rebuildRadioLinks(rangeM) { d ->
                // RSSI-derived profile: ~2% loss in-range, latency grows slightly with distance
                LinkProfile(lossFwd = 0.02, lossRev = 0.02, latencyMs = 6.0 + d * 0.06, jitterMs = 3.0)
            }
            engine.postIn(tickMs) { tick() }
        }
        engine.postIn(tickMs) { tick() }
    }
}

/** Scripted mobility (Part C: "scripted"): waypoint list per node at fixed speeds. */
class ScriptedMobility(private val world: SimWorld) {

    data class Leg(val target: Position, val speedMPs: Double)

    private val plans = HashMap<String, ArrayDeque<Leg>>()

    fun plan(nodeName: String, legs: List<Leg>) {
        plans[nodeName] = ArrayDeque(legs)
    }

    fun start(tickMs: Long = 1_000) {
        val engine = world.engine
        fun tick() {
            for ((nodeName, queue) in plans) {
                val node = world.node(nodeName)
                if (!node.up || queue.isEmpty()) continue
                val leg = queue.first()
                val pos = node.position
                val dx = leg.target.x - pos.x
                val dy = leg.target.y - pos.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val step = leg.speedMPs * tickMs / 1000.0
                if (dist <= step) {
                    node.position = leg.target
                    queue.removeFirst()
                } else {
                    node.position = Position(pos.x + dx / dist * step, pos.y + dy / dist * step)
                }
            }
            engine.postIn(tickMs) { tick() }
        }
        engine.postIn(tickMs) { tick() }
    }
}

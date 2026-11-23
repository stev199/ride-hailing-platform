package com.ridehailing.mesh.sim

import com.ridehailing.mesh.routing.CandidatePath
import com.ridehailing.mesh.routing.CostFunction
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.routing.PathHop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DijkstraOracleTest {
    private fun hop(to: String, etx: Double = 1.0, battery: Double = 0.0, load: Double = 0.0, instability: Double = 0.0) =
        PathHop(NodeAddress(to), etx, battery, load, instability)

    @Test
    fun `simple diamond picks the two short cheap hops`() {
        // r -> A -> gw (2 hops, etx 1) and r -> B -> C -> gw (3 hops, etx 1)
        val graph = mapOf(
            NodeAddress("r") to listOf(DijkstraOracle.Edge(NodeAddress("A"), hop("A")), DijkstraOracle.Edge(NodeAddress("B"), hop("B"))),
            NodeAddress("A") to listOf(DijkstraOracle.Edge(NodeAddress("gw"), hop("gw"))),
            NodeAddress("B") to listOf(DijkstraOracle.Edge(NodeAddress("C"), hop("C"))),
            NodeAddress("C") to listOf(DijkstraOracle.Edge(NodeAddress("gw"), hop("gw"))),
        )
        val cost = CostFunction(maxHops = 3)
        val result = DijkstraOracle.shortestPaths(graph, NodeAddress("r"), cost, maxHops = 3)
        val path = result.paths[NodeAddress("gw")]!!
        assertEquals(listOf("A", "gw"), path.map { it.value })
        // cost = w_hop*2 + w_etx*2 = 2 + 3 = 5
        assertEquals(5.0, result.costs[NodeAddress("gw")]!!, 1e-9)
    }

    @Test
    fun `cheaper-but-longer path wins when etx penalty dominates`() {
        // 2-hop path with bad etx (2.0 each) vs 3-hop path with perfect etx
        val graph = mapOf(
            NodeAddress("r") to listOf(
                DijkstraOracle.Edge(NodeAddress("bad"), hop("bad", etx = 2.0)),
                DijkstraOracle.Edge(NodeAddress("g1"), hop("g1")),
            ),
            NodeAddress("bad") to listOf(DijkstraOracle.Edge(NodeAddress("gw"), hop("gw", etx = 2.0))),
            NodeAddress("g1") to listOf(DijkstraOracle.Edge(NodeAddress("g2"), hop("g2"))),
            NodeAddress("g2") to listOf(DijkstraOracle.Edge(NodeAddress("gw"), hop("gw"))),
        )
        val cost = CostFunction(maxHops = 3)
        val result = DijkstraOracle.shortestPaths(graph, NodeAddress("r"), cost, maxHops = 3)
        // bad path: 2 hops + 1.5*4 = 2 + 6 = 8; good path: 3 + 1.5*3 = 7.5
        assertEquals(listOf("g1", "g2", "gw"), result.paths[NodeAddress("gw")]!!.map { it.value })
        assertEquals(7.5, result.costs[NodeAddress("gw")]!!, 1e-9)
    }

    @Test
    fun `hop budget is enforced by the oracle`() {
        val graph = mapOf(
            NodeAddress("r") to listOf(DijkstraOracle.Edge(NodeAddress("a"), hop("a"))),
            NodeAddress("a") to listOf(DijkstraOracle.Edge(NodeAddress("gw"), hop("gw"))),
        )
        val result = DijkstraOracle.shortestPaths(graph, NodeAddress("r"), CostFunction(maxHops = 1), maxHops = 1)
        assertEquals(null, result.costs[NodeAddress("gw")], "gw is 2 hops away: unreachable within budget")
    }

    /**
     * Scenario 7 preview: on seeded random topologies, the online selection cost must
     * equal the oracle optimum. Here we assert the oracle matches an exhaustive
     * best-path search over 200 seeded random graphs (full enumeration over simple
     * paths of <= maxHops hops).
     */
    @Test
    fun `oracle equals exhaustive optimum on 200 seeded random topologies`() {
        val rnd = Random(2026)
        var checked = 0
        repeat(200) {
            val nodes = (0 until 8).map { NodeAddress("n$it") }
            val source = nodes[0]
            val target = nodes[7]
            val maxHops = 4
            // Random directed graph: p=0.35, metrics random but fixed per edge.
            val graph = buildMap {
                for (from in nodes) {
                    val edges = mutableListOf<DijkstraOracle.Edge>()
                    for (to in nodes) {
                        if (to != from && rnd.nextDouble() < 0.35) {
                            val h = PathHop(
                                to,
                                etx = 1.0 + rnd.nextInt(3),
                                batteryPenalty = rnd.nextInt(3).toDouble(),
                                loadPenalty = rnd.nextDouble(),
                                instabilityPenalty = rnd.nextDouble(),
                            )
                            edges.add(DijkstraOracle.Edge(to, h))
                        }
                    }
                    put(from, edges)
                }
            }
            val costFn = CostFunction(maxHops = maxHops)
            val oracle = DijkstraOracle.shortestPaths(graph, source, costFn, maxHops = maxHops)
            val oracleCost = oracle.costs[target]
            val exhaustive = exhaustiveBest(graph, source, target, maxHops, costFn)
            if (exhaustive == null && oracleCost != null) {
                // exhaustive enumerates simple paths only; Dijkstra may also only use
                // simple paths (each hop strictly decreases the remaining budget), so
                // null must match null.
                assertTrue(false, "oracle found a path where exhaustive found none")
            }
            if (exhaustive != null && oracleCost != null) {
                assertEquals(exhaustive, oracleCost, 1e-9, "cost mismatch in seeded topology")
                checked++
            }
        }
        assertTrue(checked >= 100, "enough graphs must contain a valid path: only $checked did")
    }

    /** Exhaustive DFS over simple paths (small graphs, so tractable). */
    private fun exhaustiveBest(
        graph: Map<NodeAddress, List<DijkstraOracle.Edge>>,
        source: NodeAddress,
        target: NodeAddress,
        maxHops: Int,
        costFn: CostFunction,
    ): Double? {
        var best: Double? = null
        fun dfs(node: NodeAddress, visited: MutableSet<NodeAddress>, hops: List<PathHop>) {
            if (hops.size > maxHops) return
            if (node == target && hops.isNotEmpty()) {
                val c = costFn.cost(CandidatePath(hops))
                if (c != null && (best == null || c < best!!)) best = c
                return
            }
            for (edge in graph[node].orEmpty()) {
                if (edge.to in visited) continue
                visited.add(edge.to)
                dfs(edge.to, visited, hops + edge.hop)
                visited.remove(edge.to)
            }
        }
        dfs(source, mutableSetOf(source), emptyList())
        return best
    }
}

package com.ridehailing.mesh.sim

import com.ridehailing.mesh.routing.CandidatePath
import com.ridehailing.mesh.routing.CostFunction
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.routing.PathHop
import java.util.PriorityQueue

/**
 * Dijkstra oracle (Stage 3 requirement, built with Stage 2): computes the optimal
 * path over the SAME additive cost function on static topology snapshots. The
 * scenario suite asserts the online route discovery matches the oracle (GATE 3
 * scenario 7: chosen return path cost equals the Dijkstra optimum on 100% of 1000
 * seeded random topologies).
 */
object DijkstraOracle {

    /** Directed edge: hop metrics describe the link INTO [to] (delivery-direction aware). */
    data class Edge(val to: NodeAddress, val hop: PathHop)

    data class OracleResult(
        val costs: Map<NodeAddress, Double>,
        val paths: Map<NodeAddress, List<NodeAddress>>, // forwarding order, source-exclusive (source -> ... -> dest)
    )

    /**
     * Runs Dijkstra from [source]. Edge cost comes from [costFunction].hopCost;
     * edges from revoked nodes or over the hop budget are hard-rejected the same way
     * the online cost function rejects whole paths.
     */
    fun shortestPaths(
        graph: Map<NodeAddress, List<Edge>>,
        source: NodeAddress,
        costFunction: CostFunction,
        maxHops: Int,
    ): OracleResult {
        data class Label(val cost: Double, val hops: Int, val node: NodeAddress)

        val dist = HashMap<NodeAddress, Double>()
        val hopCount = HashMap<NodeAddress, Int>()
        val prev = HashMap<NodeAddress, NodeAddress?>()
        val visited = HashSet<NodeAddress>()
        val queue = PriorityQueue(compareBy<Label> { it.cost })

        dist[source] = 0.0
        hopCount[source] = 0
        prev[source] = null
        queue.add(Label(0.0, 0, source))

        while (queue.isNotEmpty()) {
            val label = queue.poll()
            if (label.node in visited) continue
            visited.add(label.node)
            for (edge in graph[label.node].orEmpty()) {
                if (edge.hop.revoked) continue
                val nextHops = (hopCount[label.node] ?: 0) + 1
                if (nextHops > maxHops) continue
                val candidate = (dist[label.node] ?: Double.MAX_VALUE) + costFunction.hopCost(edge.hop)
                val current = dist[edge.to]
                if (current == null || candidate < current) {
                    dist[edge.to] = candidate
                    hopCount[edge.to] = nextHops
                    prev[edge.to] = label.node
                    queue.add(Label(candidate, nextHops, edge.to))
                }
            }
        }

        val paths = HashMap<NodeAddress, List<NodeAddress>>()
        for (target in dist.keys) {
            if (target == source) continue
            val chain = mutableListOf<NodeAddress>()
            var cursor: NodeAddress? = target
            while (cursor != null && cursor != source) {
                chain.add(cursor)
                cursor = prev[cursor]
            }
            paths[target] = chain.reversed()
        }
        return OracleResult(dist, paths)
    }

    /** Converts an oracle path (node ids) into a [CandidatePath] using the edge metrics. */
    fun toCandidatePath(
        source: NodeAddress,
        target: NodeAddress,
        path: List<NodeAddress>,
        graph: Map<NodeAddress, List<Edge>>,
    ): CandidatePath {
        var cursor = source
        val hops = mutableListOf<PathHop>()
        for (node in path) {
            val edge = graph[cursor].orEmpty().firstOrNull { it.to == node }
                ?: error("oracle path breaks topology at $cursor -> $node")
            hops.add(edge.hop)
            cursor = node
        }
        check(cursor == target)
        return CandidatePath(hops)
    }
}

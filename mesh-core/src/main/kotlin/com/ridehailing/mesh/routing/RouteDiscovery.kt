package com.ridehailing.mesh.routing

/**
 * AODV-style route discovery (Stage 2). The destination (driver or gateway acting
 * for the server) collects copies of the same msgId arriving over different paths
 * during a collection window (default 800 ms, max 2 s), scores each candidate with
 * [CostFunction], hard-rejects paths containing revoked nodes, keeps the best path
 * plus a node-disjoint-as-possible backup, and installs the winner into the
 * [RouteTable].
 */
class RouteDiscovery(
    private val costFunction: CostFunction,
    private val routeTable: RouteTable,
    private val clock: MeshClock,
    private val config: Config = Config(),
) {
    data class Config(
        val collectionWindowMs: Long = 800,
        val maxCollectionWindowMs: Long = 2000,
    )

    /** One observed candidate: full forwarding order (origin -> ... -> collector). */
    data class Observation(val path: CandidatePath, val receivedAtMs: Long)

    private val collections = HashMap<SeenCache.Key, MutableList<Observation>>()

    /** Adds a candidate observed for [key]. Candidates arriving after the window end are ignored. */
    @Synchronized
    fun observe(key: SeenCache.Key, path: CandidatePath, windowOverrideMs: Long? = null) {
        require(windowOverrideMs == null || windowOverrideMs <= config.maxCollectionWindowMs) {
            "collection window above hard max ${config.maxCollectionWindowMs}"
        }
        val list = collections.getOrPut(key) { mutableListOf() }
        list.add(Observation(path, clock.nowMs()))
    }

    sealed class Selection {
        data class Selected(
            val destination: NodeAddress,
            val best: CandidatePath,
            val bestCost: Double,
            val backup: CandidatePath?,
            val backupCost: Double?,
        ) : Selection()

        /** No valid candidate (all revoked/over-budget, or nothing observed). */
        data class None(val reason: String) : Selection()
    }

    /**
     * Closes the collection for [key] and selects best + node-disjoint-as-possible
     * backup. The caller triggers this when the collection window elapses.
     */
    @Synchronized
    fun select(key: SeenCache.Key, destination: NodeAddress): Selection {
        val candidates = collections.remove(key) ?: return Selection.None("no_candidates")
        val scored = candidates
            .distinctBy { it.path.hops.map { hop -> hop.node.value } }
            .mapNotNull { obs -> costFunction.cost(obs.path)?.let { obs.path to it } }
            .sortedWith(compareBy({ it.second }, { it.first.hops.size }))
        if (scored.isEmpty()) return Selection.None("all_candidates_rejected")
        val (best, bestCost) = scored.first()
        val backup = scored.drop(1).firstOrNull { (path, _) -> isNodeDisjoint(best, path) }?.let { (path, cost) -> path to cost }
        val backupPath = backup?.first ?: disjointByCostFallback(best, scored.drop(1))
        val backupCost = backup?.second
        routeTable.update(destination, best, backupPath, bestCost)
        return Selection.Selected(destination, best, bestCost, backupPath, backupCost)
    }

    private fun isNodeDisjoint(a: CandidatePath, b: CandidatePath): Boolean {
        val aNodes = a.intermediate.map { it.value }.toSet()
        val bNodes = b.intermediate.map { it.value }.toSet()
        return aNodes.intersect(bNodes).isEmpty()
    }

    /**
     * Spec: backup is "node-disjoint-as-possible". When no disjoint candidate exists,
     * take the next-best candidate that maximizes node overlap distance; ties by cost.
     */
    private fun disjointByCostFallback(best: CandidatePath, rest: List<Pair<CandidatePath, Double>>): CandidatePath? {
        if (rest.isEmpty()) return null
        val bestNodes = best.intermediate.map { it.value }.toSet()
        return rest.maxWith(
            compareBy(
                { (path, _) -> bestNodes.intersect(path.intermediate.map { it.value }.toSet()).size * -1 },
                { (_, cost) -> -cost },
            ),
        ).first
    }
}

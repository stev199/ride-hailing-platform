package com.ridehailing.mesh.routing

/**
 * Per-link quality metrics (Stage 2 cost inputs).
 *
 * ETX (expected transmissions) over a link = 1 / (deliveryFwd * deliveryRev), tracked
 * as EWMA with alpha 0.3 (spec), clamped to [1, 10]; initial estimate derived from
 * RSSI. Battery/load/instability penalties follow the Part C Stage 2 formula exactly.
 */
class LinkMetrics {

    data class EtxState(var forwardDelivery: Double = 1.0, var reverseDelivery: Double = 1.0)

    /**
     * RSSI -> initial ETX. Mapping (documented choice): >= -55 dBm strong -> 1.0;
     * every ~10 dB worse adds one expected retransmission until the clamp.
     */
    fun initialEtxFromRssi(rssiDbm: Int): Double {
        val clamped = rssiDbm.coerceIn(-100, -30)
        val steps = ((-55 - clamped).coerceAtLeast(0) + 9) / 10 // ceiling: each ~10 dB worse adds one
        return 1.0 + steps
    }

    /**
     * EWMA update (alpha 0.3) of forward/reverse delivery probability from an ACK or
     * beacon outcome. [forwardOk]/[reverseOk] are direct observations (1.0/0.0).
     */
    fun update(state: EtxState, forwardOk: Boolean?, reverseOk: Boolean?, alpha: Double = 0.3) {
        forwardOk?.let { observed ->
            state.forwardDelivery = alpha * (if (observed) 1.0 else 0.0) + (1 - alpha) * state.forwardDelivery
        }
        reverseOk?.let { observed ->
            state.reverseDelivery = alpha * (if (observed) 1.0 else 0.0) + (1 - alpha) * state.reverseDelivery
        }
    }

    fun etx(state: EtxState): Double {
        val delivery = (state.forwardDelivery * state.reverseDelivery).coerceIn(0.01, 1.0)
        return (1.0 / delivery).coerceIn(1.0, 10.0)
    }

    /** Battery penalty (spec): 0 / 1 / 3 / 8; <5% excluded by callers unless forced. */
    fun batteryPenalty(batteryPercent: Int, charging: Boolean): Double = when {
        charging || batteryPercent > 50 -> 0.0
        batteryPercent in 20..50 -> 1.0
        batteryPercent in 10..19 -> 3.0
        batteryPercent in 5..9 -> 8.0
        else -> 8.0 // <5%: callers hard-exclude unless no alternative exists
    }

    /** Load penalty (spec): queueLength/queueCapacity * 2. */
    fun loadPenalty(queueLength: Int, queueCapacity: Int): Double {
        if (queueCapacity <= 0) return 2.0 // saturated by definition
        return (queueLength.toDouble() / queueCapacity * 2.0).coerceIn(0.0, 2.0)
    }

    /**
     * Instability penalty (spec): 0 for links continuously seen > 60 s; up to 3 for
     * links first seen < 5 s ago or with high RSSI variance.
     */
    fun instabilityPenalty(linkAgeMs: Long, rssiVarianceDb: Double): Double {
        val agePenalty = when {
            linkAgeMs > 60_000 -> 0.0
            linkAgeMs < 5_000 -> 3.0
            else -> 3.0 * (60_000.0 - linkAgeMs) / 55_000.0 // linear ramp 3 -> 0
        }
        if (rssiVarianceDb > 6.0) return 3.0 // high variance alone is maximal instability
        return agePenalty.coerceAtMost(3.0)
    }

    /** Gateway staleness penalty (spec): secondsSinceLastGatewayAnnounce/30 capped at 3. */
    fun gatewayStalenessPenalty(lastAnnounceMsAgo: Long?): Double {
        if (lastAnnounceMsAgo == null) return 3.0 // never announced: maximal staleness
        return (lastAnnounceMsAgo / 30_000.0).coerceAtMost(3.0)
    }

}

/**
 * Default weights from the spec (all overridable from signed admin config).
 */
data class Weights(
    val hop: Double = 1.0,
    val etx: Double = 1.5,
    val batt: Double = 0.8,
    val load: Double = 0.6,
    val stab: Double = 0.8,
    val lat: Double = 0.3,
    val gw: Double = 0.5,
)

/**
 * One hop of a candidate path with the metrics snapshot used for scoring.
 * [latencyMs] is the measured one-hop latency (simulator feeds real values).
 */
data class PathHop(
    val node: NodeAddress,
    val etx: Double,
    val batteryPenalty: Double,
    val loadPenalty: Double,
    val instabilityPenalty: Double,
    val latencyMs: Double = 0.0,
    val revoked: Boolean = false,
)

/** Candidate path in forwarding order (origin -> ... -> destination). */
data class CandidatePath(val hops: List<PathHop>) {
    val intermediate: List<NodeAddress> get() = hops.dropLast(1).map { it.node }
    val destination: NodeAddress? get() = hops.lastOrNull()?.node
}

/**
 * Path cost (spec Stage 2):
 * cost = w_hop*H + w_etx*sum(ETX_i) + w_batt*sum(battPenalty_i) + w_load*sum(loadPenalty_i)
 *      + w_stab*sum(instabilityPenalty_i) + w_lat*latencyMs/100 + w_gw*gatewayStalenessPenalty
 *
 * Invalid paths (revoked node present, hop budget exceeded) return null.
 */
class CostFunction(
    private val weights: Weights = Weights(),
    private val maxHops: Int = 3,
    private val gatewayStalenessMsAgo: Long? = null,
    private val isGatewayRoute: Boolean = false,
) {
    private val metrics = LinkMetrics()

    /** Returns null when the path must be hard-rejected. */
    fun cost(path: CandidatePath): Double? {
        if (path.hops.size > maxHops) return null
        if (path.hops.any { it.revoked }) return null // hard-reject revoked nodes
        var cost = weights.hop * path.hops.size
        cost += weights.etx * path.hops.sumOf { it.etx }
        cost += weights.batt * path.hops.sumOf { it.batteryPenalty }
        cost += weights.load * path.hops.sumOf { it.loadPenalty }
        cost += weights.stab * path.hops.sumOf { it.instabilityPenalty }
        cost += weights.lat * path.hops.sumOf { it.latencyMs } / 100.0
        if (isGatewayRoute) {
            cost += weights.gw * metrics.gatewayStalenessPenalty(gatewayStalenessMsAgo)
        }
        return cost
    }

    /** Per-hop cost for Dijkstra-style additive comparison on static snapshots. */
    fun hopCost(hop: PathHop): Double {
        var c = weights.hop
        c += weights.etx * hop.etx
        c += weights.batt * hop.batteryPenalty
        c += weights.load * hop.loadPenalty
        c += weights.stab * hop.instabilityPenalty
        c += weights.lat * hop.latencyMs / 100.0
        return c
    }
}

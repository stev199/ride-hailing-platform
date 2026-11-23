package com.ridehailing.mesh.sim

/**
 * Stage 3 metrics (Part C): delivery ratio, latency p50/p95, transmissions per
 * delivered message, path stretch, failover time - plus scenario-specific counters.
 * All values are produced deterministically (virtual time + seeded RNG).
 */
class RunMetrics {
    var transmissions = 0L
    var lostTransmissions = 0L
    var requestsSent = 0L
    var requestsDeliveredAtDriver = 0L
    var quotesDelivered = 0L
    var appDuplicates = 0L
    var noRoute = 0L
    var relayBudgetViolations = 0L
    var rateLimitedDrops = 0L
    var revokedDrops = 0L
    var replayDrops = 0L
    var queuedDelivered = 0L
    var queuedExpired = 0L
    var floodCompletionRatio = 0.0

    /** Validator/relay drops by machine-readable reason (bad signature, hop budget, ...). */
    val invalidDrops = mutableMapOf<String, Int>()
    /** Relay count per node name (ring scenario: no packet circulates more than once). */
    val relaysByNode = HashMap<String, Long>()

    val latenciesMs = mutableListOf<Double>()
    val failoverTimesMs = mutableListOf<Double>()
    val pathStretchHops = mutableListOf<Double>() // chosen return hops / oracle hops
    val pathCostStretch = mutableListOf<Double>() // chosen return cost / oracle cost
    val floodTransmissions = mutableListOf<Long>() // per-request flood tx counts
    val forwardPathHops = mutableListOf<Long>() // observed forward path length per app delivery
    val quoteOriginByTag = HashMap<String, String>() // tag -> quoting node originId hex
    val chosenFirstHops = mutableListOf<String>() // first relay of the chosen return path per selection

    fun latencyP(percent: Int): Double = percentile(latenciesMs, percent)
    fun transmissionsPerDelivered(): Double =
        if (quotesDelivered == 0L) 0.0 else transmissions.toDouble() / quotesDelivered

    fun summary(): Map<String, Double> = linkedMapOf(
        "deliveryRatio" to ratio(quotesDelivered, requestsSent),
        "requestDeliveryRatio" to ratio(requestsDeliveredAtDriver, requestsSent),
        "latencyP50Ms" to latencyP(50),
        "latencyP95Ms" to latencyP(95),
        "transmissionsPerDelivered" to transmissionsPerDelivered(),
        "transmissions" to transmissions.toDouble(),
        "lostTransmissions" to lostTransmissions.toDouble(),
        "appDuplicates" to appDuplicates.toDouble(),
        "noRoute" to noRoute.toDouble(),
        "relayBudgetViolations" to relayBudgetViolations.toDouble(),
        "rateLimitedDrops" to rateLimitedDrops.toDouble(),
        "revokedDrops" to revokedDrops.toDouble(),
        "replayDrops" to replayDrops.toDouble(),
        "queuedDelivered" to queuedDelivered.toDouble(),
        "queuedExpired" to queuedExpired.toDouble(),
        "invalidDropsTotal" to invalidDrops.values.sum().toDouble(),
        "budgetEnforced" to (invalidDrops["hop budget exhausted"] ?: 0).toDouble(),
        "maxRelaysPerNode" to (relaysByNode.values.maxOrNull() ?: 0L).toDouble(),
        "circulationViolations" to relaysByNode.values.count { it > 1 }.toDouble(),
        "avgForwardPathHops" to avg(forwardPathHops.map { it.toDouble() }),
        "floodCompletionRatio" to floodCompletionRatio,
        "avgPathStretchHops" to avg(pathStretchHops),
        "avgPathCostStretch" to avg(pathCostStretch),
        "maxFailoverMs" to (failoverTimesMs.maxOrNull() ?: 0.0),
    )

    private fun ratio(num: Long, den: Long): Double = if (den == 0L) 0.0 else num.toDouble() / den

    companion object {
        fun percentile(values: List<Double>, percent: Int): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val idx = (kotlin.math.ceil(percent / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        fun avg(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.average()
    }
}

/** Metric direction for baseline regression checks (GATE 3: >10% regression fails the build). */
enum class MetricDirection { HIGHER_IS_BETTER, LOWER_IS_BETTER, EXACT }

object MetricDirections {
    val LOWER_IS_BETTER = setOf(
        "latencyP50Ms", "latencyP95Ms", "transmissionsPerDelivered", "txPerNode",
        "stormTxRatio", "maxFailoverMs", "avgPathStretchHops", "avgPathCostStretch",
        "maxRelaysPerNode", "lostRequests", "deliveryLossCount",
    )
    val EXACT = setOf(
        "noRoute", "queuedExpired", "queuedDelivered", "appDuplicates", "relayBudgetViolations",
        "rateLimitedDrops", "revokedDrops", "replayDrops", "hopCountCorrect",
        "liveMaxHops", "staleRejected", "expiredRejected", "circulationViolations",
    )
    fun directionOf(metric: String): MetricDirection = when {
        metric in LOWER_IS_BETTER -> MetricDirection.LOWER_IS_BETTER
        metric in EXACT -> MetricDirection.EXACT
        else -> MetricDirection.HIGHER_IS_BETTER
    }
}

/** A pass/fail threshold attached to one metric of a scenario result. */
data class Threshold(
    val metric: String,
    val min: Double? = null,
    val max: Double? = null,
    val exactly: Double? = null,
    val note: String = "",
)

data class ScenarioResult(
    val name: String,
    val seed: Long,
    val metrics: Map<String, Double>,
    val thresholds: List<Threshold>,
    val notes: List<String> = emptyList(),
) {
    fun failures(): List<String> {
        val out = mutableListOf<String>()
        for (t in thresholds) {
            val v = metrics[t.metric]
            if (v == null) {
                out.add("${t.metric}: missing from metrics")
                continue
            }
            if (t.exactly != null && v != t.exactly) out.add("${t.metric}=$v expected exactly ${t.exactly} (${t.note})")
            if (t.min != null && v < t.min) out.add("${t.metric}=$v below minimum ${t.min} (${t.note})")
            if (t.max != null && v > t.max) out.add("${t.metric}=$v above maximum ${t.max} (${t.note})")
        }
        return out
    }
}

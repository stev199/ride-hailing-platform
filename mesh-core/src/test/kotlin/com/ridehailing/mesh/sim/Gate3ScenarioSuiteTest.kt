package com.ridehailing.mesh.sim

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GATE 3 CI gate: all 15 mandatory scenarios with fixed seeds run on every commit
 * (failing scenario blocks merge), plus the baseline.json regression gate
 * (any metric regressing > 10% fails the build - Part C Stage 3).
 */
class Gate3ScenarioSuiteTest {

    private fun result(name: String): ScenarioResult =
        SuiteCache.results().firstOrNull { it.name == name }
            ?: error("scenario $name missing from suite")

    private fun assertPasses(r: ScenarioResult) {
        val failures = r.failures()
        assertTrue(failures.isEmpty(), "${r.name} failed thresholds: $failures\nmetrics=${r.metrics}")
    }

    @Test
    fun `scenario 1 - line 1 to maxHops lossless - 100 percent both ways`() = assertPasses(result("1-line-lossless"))

    @Test
    fun `scenario 2 - line beyond maxHops - clean no route`() = assertPasses(result("2-line-beyond-maxhops"))

    @Test
    fun `scenario 3 - star and grid flood completes under 2_5 transmissions per node`() = assertPasses(result("3-star-grid-flood"))

    @Test
    fun `scenario 4 - ring - no packet circulates more than once per node`() = assertPasses(result("4-ring-no-circulation"))

    @Test
    fun `scenario 5 - broadcast storm - delivery and transmission bounds`() = assertPasses(result("5-broadcast-storm"))

    @Test
    fun `scenario 6 - lossy links 10 30 50 percent with retries`() = assertPasses(result("6-lossy-links"))

    @Test
    fun `scenario 7 - 1000 topologies - chosen path equals dijkstra optimum`() = assertPasses(result("7-static-multipath-oracle"))

    @Test
    fun `scenario 8 - relay failure mid reply - backup within 3 s`() = assertPasses(result("8-relay-failure-mid-reply"))

    @Test
    fun `scenario 9 - mobility walking and driving - 90 percent at 3 hops`() = assertPasses(result("9-mobility"))

    @Test
    fun `scenario 10 - partition and heal - queued deliver within ttl expired reported`() = assertPasses(result("10-partition-heal"))

    @Test
    fun `scenario 11 - gateway churn - no loss within ttl`() = assertPasses(result("11-gateway-churn"))

    @Test
    fun `scenario 12 - malicious nodes - all dropped honest within 5 percent`() = assertPasses(result("12-malicious-nodes"))

    @Test
    fun `scenario 13 - config change - lower maxHops applies live stale rejected`() = assertPasses(result("13-config-change"))

    @Test
    fun `scenario 14 - clock skew plus minus 5 min - policy handles hint corrects`() = assertPasses(result("14-clock-skew"))

    @Test
    fun `scenario 15 - fragmentation under loss reordering - bounded memory`() = assertPasses(result("15-fragmentation"))

    @Test
    fun `baseline regression gate - no metric regresses more than 10 percent`() {
        val baselineFile = java.io.File("baseline.json")
        val results = SuiteCache.results()
        if (!baselineFile.exists()) {
            baselineFile.writeText(BaselineCheck.toJson(results))
            println("baseline.json initialized (first run); commit it to enable the regression gate")
            return
        }
        val regressions = BaselineCheck.compare(results, BaselineCheck.parse(baselineFile.readText()))
        assertTrue(regressions.isEmpty(), "baseline regressions:\n${regressions.joinToString("\n")}")
    }
}

package com.ridehailing.mesh.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeenCacheTest {
    @Test
    fun `first sighting forwards and duplicates are rejected`() {
        val cache = SeenCache()
        val key = SeenCache.Key("origin-a".toByteArray(), "msg-1".toByteArray())
        assertTrue(cache.see(key, nowMs = 0), "first sight must forward")
        assertFalse(cache.see(key, nowMs = 10), "duplicate must not forward")
    }

    @Test
    fun `entries expire after ttl and may be re-seen`() {
        val cache = SeenCache(ttlMs = 1000)
        val key = SeenCache.Key("o".toByteArray(), "m".toByteArray())
        assertTrue(cache.see(key, 0))
        assertFalse(cache.see(key, 500))
        assertTrue(cache.see(key, 1001), "expired entry re-seen is treated as new")
    }

    @Test
    fun `lru cap evicts oldest entries`() {
        val cache = SeenCache(capacity = 3, ttlMs = 60_000)
        val keys = (0 until 5).map { SeenCache.Key("o".toByteArray(), "m$it".toByteArray()) }
        keys.forEachIndexed { i, k -> cache.see(k, i.toLong()) }
        assertEquals(3, cache.size())
        assertFalse(cache.seen(keys[0], 100), "oldest evicted")
        assertFalse(cache.seen(keys[1], 100), "second evicted")
        assertTrue(cache.seen(keys[2], 100), "within cap retained")
        assertTrue(cache.seen(keys[4], 100))
    }
}

class OriginRateLimiterTest {
    @Test
    fun `allows up to max per window then refuses`() {
        val limiter = OriginRateLimiter(maxPerWindow = 3, windowMs = 10_000)
        val origin = "o".toByteArray()
        assertTrue(limiter.tryAcquire(origin, 0))
        assertTrue(limiter.tryAcquire(origin, 1))
        assertTrue(limiter.tryAcquire(origin, 2))
        assertFalse(limiter.tryAcquire(origin, 3), "4th inside window refused")
        assertTrue(limiter.tryAcquire(origin, 10_001), "window slid: slot freed")
    }

    @Test
    fun `different origins have independent budgets`() {
        val limiter = OriginRateLimiter(maxPerWindow = 1, windowMs = 10_000)
        assertTrue(limiter.tryAcquire("a".toByteArray(), 0))
        assertFalse(limiter.tryAcquire("a".toByteArray(), 1))
        assertTrue(limiter.tryAcquire("b".toByteArray(), 2))
    }
}

class LinkMetricsTest {
    private val metrics = LinkMetrics()

    @Test
    fun `rssi maps to initial etx estimate`() {
        assertEquals(1.0, metrics.initialEtxFromRssi(-50))
        assertEquals(1.0, metrics.initialEtxFromRssi(-55))
        assertEquals(2.0, metrics.initialEtxFromRssi(-60))
        assertEquals(4.0, metrics.initialEtxFromRssi(-85))
        assertEquals(6.0, metrics.initialEtxFromRssi(-100), "clamped at -100 => 6.0")
    }

    @Test
    fun `etx ewma converges and clamps between 1 and 10`() {
        val state = LinkMetrics.EtxState()
        metrics.update(state, forwardOk = false, reverseOk = false)
        repeat(20) { metrics.update(state, forwardOk = false, reverseOk = false) }
        val etx = metrics.etx(state)
        assertTrue(etx <= 10.0 && etx >= 1.0, "clamped: $etx")
        assertEquals(10.0, etx, "all failures converge to max ETX")
        repeat(20) { metrics.update(state, forwardOk = true, reverseOk = true) }
        assertTrue(kotlin.math.abs(metrics.etx(state) - 1.0) < 0.01, "converges to min ETX: ${metrics.etx(state)}")
    }

    @Test
    fun `battery penalties follow spec classes`() {
        assertEquals(0.0, metrics.batteryPenalty(80, charging = false))
        assertEquals(0.0, metrics.batteryPenalty(30, charging = true))
        assertEquals(1.0, metrics.batteryPenalty(35, charging = false))
        assertEquals(3.0, metrics.batteryPenalty(15, charging = false))
        assertEquals(8.0, metrics.batteryPenalty(7, charging = false))
        assertEquals(8.0, metrics.batteryPenalty(3, charging = false))
    }

    @Test
    fun `load penalty is queue ratio doubled`() {
        assertEquals(0.0, metrics.loadPenalty(0, 10))
        assertEquals(1.0, metrics.loadPenalty(5, 10))
        assertEquals(2.0, metrics.loadPenalty(10, 10))
        assertEquals(2.0, metrics.loadPenalty(20, 10), "overfull clamps at 2")
    }

    @Test
    fun `instability ramps from 3 to 0 across 5s-60s`() {
        assertEquals(3.0, metrics.instabilityPenalty(1_000, 0.0))
        assertTrue(metrics.instabilityPenalty(30_000, 0.0) in 1.0..2.0)
        assertEquals(0.0, metrics.instabilityPenalty(61_000, 0.0))
        assertEquals(3.0, metrics.instabilityPenalty(61_000, 10.0), "variance pushes to cap 3")
    }

    @Test
    fun `gateway staleness per spec with cap and never-announced max`() {
        assertEquals(0.0, metrics.gatewayStalenessPenalty(0))
        assertEquals(1.0, metrics.gatewayStalenessPenalty(30_000))
        assertEquals(3.0, metrics.gatewayStalenessPenalty(600_000), "capped at 3")
        assertEquals(3.0, metrics.gatewayStalenessPenalty(null), "never announced")
    }
}

class CostFunctionTest {
    private fun hop(
        node: String,
        etx: Double = 1.0,
        battery: Double = 0.0,
        load: Double = 0.0,
        instability: Double = 0.0,
        latencyMs: Double = 0.0,
        revoked: Boolean = false,
    ) = PathHop(NodeAddress(node), etx, battery, load, instability, latencyMs, revoked)

    @Test
    fun `spec weights produce documented cost on a clean 3-hop path`() {
        val cost = CostFunction(maxHops = 3).cost(
            CandidatePath(listOf(hop("a"), hop("b"), hop("c"))),
        )
        // w_hop*3 + w_etx*3 = 3*1.0 + 3*1.5 = 7.5 (all penalties 0)
        assertEquals(7.5, cost!!, 1e-9)
    }

    @Test
    fun `penalties add with their weights`() {
        val cost = CostFunction(maxHops = 2).cost(
            CandidatePath(listOf(hop("a", etx = 2.0, battery = 1.0, load = 1.0, instability = 3.0, latencyMs = 100.0))),
        )
        // 1.0 + 1.5*2 + 0.8*1 + 0.6*1 + 0.8*3 + 0.3*100/100 = 1 + 3 + 0.8 + 0.6 + 2.4 + 0.3 = 8.1
        assertEquals(8.1, cost!!, 1e-9)
    }

    @Test
    fun `paths with revoked nodes are hard-rejected`() {
        val cost = CostFunction().cost(CandidatePath(listOf(hop("a", revoked = true), hop("b"))))
        assertEquals(null, cost)
    }

    @Test
    fun `paths over the hop budget are rejected`() {
        val cost = CostFunction(maxHops = 3).cost(
            CandidatePath((1..4).map { hop("n$it") }),
        )
        assertEquals(null, cost)
    }

    @Test
    fun `gateway staleness applies once per gateway-bound path only`() {
        val base = CostFunction(maxHops = 2).cost(CandidatePath(listOf(hop("a"), hop("b"))))!!
        val withGw = CostFunction(maxHops = 2, gatewayStalenessMsAgo = 30_000, isGatewayRoute = true)
            .cost(CandidatePath(listOf(hop("a"), hop("b"))))!!
        assertEquals(base + 0.5 * 1.0, withGw, 1e-9, "w_gw * staleness(30s)=1.0")
        val nonGw = CostFunction(maxHops = 2, gatewayStalenessMsAgo = 30_000, isGatewayRoute = false)
            .cost(CandidatePath(listOf(hop("a"), hop("b"))))!!
        assertEquals(base, nonGw, "staleness term excluded for non-gateway routes")
    }
}

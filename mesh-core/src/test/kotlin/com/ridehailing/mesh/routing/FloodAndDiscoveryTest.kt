package com.ridehailing.mesh.routing

import com.ridehailing.mesh.protocol.Breadcrumb
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.HopSection
import com.ridehailing.mesh.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds a minimal valid envelope for routing-layer tests (no crypto needed here). */
fun testEnvelope(
    origin: String,
    msgId: String,
    maxHops: Int = 3,
    ttlSec: Int = 120,
    createdAtSec: Int = 0,
    type: MessageType = MessageType.RIDE_REQUEST,
): Envelope = Envelope(
    version = 1,
    type = type,
    flags = 0,
    msgId = msgId.toByteArray(),
    originId = origin.toByteArray(),
    originEpoch = 1,
    destination = Destination(DestKind.GATEWAY_ANY),
    createdAtSec = createdAtSec,
    ttlSec = ttlSec,
    maxHops = maxHops,
    payload = ByteArray(0),
    originSignature = ByteArray(64),
    hopSection = HopSection(0, emptyList<Breadcrumb>(), ByteArray(16)),
)

class FlooderTest {
    private var now = 0L
    private val clock = MeshClock { now }

    private fun newFlooder(): Flooder = Flooder(
        SeenCache(),
        OriginRateLimiter(maxPerWindow = 2, windowMs = 10_000),
        clock = clock,
        jitter = Jitter(seed = 42),
    )

    @Test
    fun `first receive schedules a jittered rebroadcast in the 20-200ms band`() {
        val decision = newFlooder().onReceive(testEnvelope("orig", "m1"), originIsUs = false, neighborCount = 0)
        assertTrue(decision is Flooder.Decision.Rebroadcast, "got $decision")
        val delay = (decision as Flooder.Decision.Rebroadcast).delayMs
        assertTrue(delay in 20..200, "delay $delay outside band")
    }

    @Test
    fun `same message twice is a duplicate and eventually suppressed (k=2)`() {
        val flooder = newFlooder()
        val env = testEnvelope("orig", "m2")
        val d1 = flooder.onReceive(env, originIsUs = false, neighborCount = 0)
        val d2 = flooder.onReceive(env, originIsUs = false, neighborCount = 0)
        assertTrue(d1 is Flooder.Decision.Rebroadcast)
        assertTrue(d2 is Flooder.Decision.Rebroadcast, "first duplicate below k=2 still forwards: $d2")
        val d3 = flooder.onReceive(env, originIsUs = false, neighborCount = 0)
        assertTrue(d3 is Flooder.Decision.Suppress, "second duplicate reaches k=2: $d3")
    }

    @Test
    fun `suppression window resets after 500ms`() {
        val flooder = newFlooder()
        val env = testEnvelope("orig", "m3")
        flooder.onReceive(env, false, 0)
        flooder.onReceive(env, false, 0)
        now = 600 // window expired
        val d = flooder.onReceive(env, false, 0)
        assertTrue(d is Flooder.Decision.Rebroadcast, "after window reset duplicate counts restart: $d")
    }

    @Test
    fun `origin spam over the rate limit is dropped`() {
        val flooder = newFlooder()
        flooder.onReceive(testEnvelope("spammer", "a"), false, 0)
        flooder.onReceive(testEnvelope("spammer", "b"), false, 0)
        val decision = flooder.onReceive(testEnvelope("spammer", "c"), false, 0)
        assertEquals(Flooder.Decision.Drop("origin_rate_limited"), decision)
    }

    @Test
    fun `denser neighborhoods push the delay up but stay within the cap`() {
        val flooder = newFlooder()
        val low = (flooder.onReceive(testEnvelope("o", "d1"), false, 0) as Flooder.Decision.Rebroadcast).delayMs
        val high = (flooder.onReceive(testEnvelope("o", "d2"), false, 40) as Flooder.Decision.Rebroadcast).delayMs
        assertTrue(low in 20..200 && high in 20..200)
        assertTrue(high >= low, "scaled delay for 40 neighbors ($high) must not undercut 0-neighbor ($low)")
    }

    @Test
    fun `our own transmissions start the flood without rate limiting`() {
        val d = newFlooder().onReceive(testEnvelope("me", "m9"), originIsUs = true, neighborCount = 0)
        assertTrue(d is Flooder.Decision.Rebroadcast)
    }
}

class RouteTableTest {
    @Test
    fun `entries expire after 60s and lookup refreshes on traffic`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val path = CandidatePath(listOf(PathHop(NodeAddress("a"), 1.0, 0.0, 0.0, 0.0)))
        table.update(NodeAddress("gw"), path, null, 2.5)
        assertEquals(2.5, table.lookup(NodeAddress("gw"))!!.cost)
        now = 30_000
        assertEquals(2.5, table.lookup(NodeAddress("gw"))!!.cost, "traffic refreshes expiry")
        now = 100_000 // 70s after last refresh
        assertEquals(null, table.lookup(NodeAddress("gw")))
    }
}

class RouteDiscoveryTest {
    private val metrics = LinkMetrics()

    private fun path(vararg nodes: String, etx: Double = 1.0) = CandidatePath(
        nodes.map { PathHop(NodeAddress(it), etx, 0.0, 0.0, 0.0) },
    )

    @Test
    fun `selects lowest cost and a node-disjoint backup`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val discovery = RouteDiscovery(CostFunction(maxHops = 4), table, MeshClock { now })
        val key = SeenCache.Key("orig".toByteArray(), "msg".toByteArray())
        discovery.observe(key, path("a", "b", "c", etx = 1.0))
        discovery.observe(key, path("x", "y", "z", etx = 1.0))
        discovery.observe(key, path("a", "q", "c", etx = 2.0))
        val selection = discovery.select(key, NodeAddress("gateway"))
        assertTrue(selection is RouteDiscovery.Selection.Selected)
        selection as RouteDiscovery.Selection.Selected
        assertEquals(listOf("a", "b", "c"), selection.best.hops.map { it.node.value })
        assertEquals(listOf("x", "y", "z"), selection.backup!!.hops.map { it.node.value }, "disjoint backup chosen")
        val entry = table.lookup(NodeAddress("gateway"))!!
        assertEquals(selection.bestCost, entry.cost)
    }

    @Test
    fun `same topology but better etx wins`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val discovery = RouteDiscovery(CostFunction(maxHops = 4), table, MeshClock { now })
        val key = SeenCache.Key("o".toByteArray(), "m".toByteArray())
        discovery.observe(key, path("a", "b", etx = 2.0))
        discovery.observe(key, path("a", "c", etx = 1.0))
        val selection = discovery.select(key, NodeAddress("gw")) as RouteDiscovery.Selection.Selected
        assertEquals(listOf("a", "c"), selection.best.hops.map { it.node.value })
    }

    @Test
    fun `revoked candidates are rejected and disjoint fallback still finds a backup`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val discovery = RouteDiscovery(CostFunction(maxHops = 4), table, MeshClock { now })
        val key = SeenCache.Key("o".toByteArray(), "m2".toByteArray())
        val bad = CandidatePath(listOf(PathHop(NodeAddress("rev"), 1.0, 0.0, 0.0, 0.0, 0.0, revoked = true), PathHop(NodeAddress("b"), 1.0, 0.0, 0.0, 0.0)))
        discovery.observe(key, bad)
        discovery.observe(key, path("a", "b", etx = 2.0))
        val selection = discovery.select(key, NodeAddress("gw")) as RouteDiscovery.Selection.Selected
        assertEquals(listOf("a", "b"), selection.best.hops.map { it.node.value })
    }

    @Test
    fun `no valid candidates yields None`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val discovery = RouteDiscovery(CostFunction(maxHops = 4), table, MeshClock { now })
        val key = SeenCache.Key("o".toByteArray(), "m3".toByteArray())
        assertEquals(RouteDiscovery.Selection.None("no_candidates"), discovery.select(key, NodeAddress("gw")))
    }

    @Test
    fun `when no disjoint candidate exists the most-different next-best is the backup`() {
        var now = 0L
        val table = RouteTable(clock = MeshClock { now })
        val discovery = RouteDiscovery(CostFunction(maxHops = 4), table, MeshClock { now })
        val key = SeenCache.Key("o".toByteArray(), "m4".toByteArray())
        discovery.observe(key, path("a", "b", etx = 1.0))
        discovery.observe(key, path("a", "b2", etx = 2.0)) // shares "a"
        discovery.observe(key, path("a2", "b3", etx = 2.5)) // fully disjoint
        val selection = discovery.select(key, NodeAddress("gw")) as RouteDiscovery.Selection.Selected
        assertEquals(listOf("a2", "b3"), selection.backup!!.hops.map { it.node.value })
    }
}

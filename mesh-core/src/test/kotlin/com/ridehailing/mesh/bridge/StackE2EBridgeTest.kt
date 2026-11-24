package com.ridehailing.mesh.bridge

import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.Validator
import com.ridehailing.mesh.routing.CancelHandle
import com.ridehailing.mesh.routing.Flooder
import com.ridehailing.mesh.routing.Jitter
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.routing.OriginRateLimiter
import com.ridehailing.mesh.routing.RealTime
import com.ridehailing.mesh.routing.SeenCache
import com.ridehailing.mesh.sim.Position
import com.ridehailing.mesh.transport.MeshTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1B-5 Stage 4 wiring: the REAL app-side stack (SeenCache + Flooder on a
 * [MeshTransport]) running end-to-end over real sockets through the host
 * [SimBridgeServer]. [BridgeNode] is the real-time twin of the simulator's
 * SimNode - exactly the wiring a GATE 4 emulator app executes (the same
 * primitives Android will run, over the same bridge; only the radio is host-side).
 *
 * Asserted here (transport-level frame relay is already pinned by [SimBridgeTest]):
 * - multi-hop flood through 2 radio hops with a byte-exact envelope and the
 *   immediate radio peer (not the origin) as the transport-level sender;
 * - seen-cache dedup over the wire: the origin hears its own message relayed back,
 *   the app sees it exactly once, and no node ever re-relays (loop prevention);
 * - the response leg: an addressed QUOTE returns to the rider node.
 */
class StackE2EBridgeTest {

    private val servers = mutableListOf<SimBridgeServer>()
    private val nodes = mutableListOf<BridgeNode>()

    private class ManualClock : java.util.concurrent.atomic.AtomicReference<Double>(0.0), () -> Double {
        override fun invoke(): Double = get()
    }

    /**
     * Real-time node harness - the Stage 4 app skeleton. Mirrors SimNode's
     * receive pipeline: seen-check (read-only) -> app delivery once ->
     * Flooder decision -> first-sighting rebroadcast scheduled with the jitter
     * delay, Suppress cancels a still-pending rebroadcast, duplicates never re-send.
     */
    private class BridgeNode(
        val nodeId: String,
        val originId: ByteArray,
        private val transport: BridgeMeshTransport,
        jitterSeed: Long,
        private val onAppEnvelope: (Envelope, NodeAddress, BridgeNode) -> Unit = { _, _, _ -> },
    ) : MeshTransport.Listener {
        private val seen = SeenCache()
        private val flooder = Flooder(seen, OriginRateLimiter(), Flooder.Config(), RealTime.clock, Jitter(jitterSeed))
        private val pending = ConcurrentHashMap<SeenCache.Key, CancelHandle>()
        private val scheduler = RealTime.scheduler

        /** App-level deliveries (first sighting of each msgId only). */
        val appDelivered = ConcurrentLinkedQueue<Pair<NodeAddress, Envelope>>()
        /** Wire-level deliveries including duplicates (transport layer truth). */
        val wireSeen = ConcurrentLinkedQueue<Pair<NodeAddress, Envelope>>()
        /** Flooder decisions, "DecisionSimpleName" strings, arrival order. */
        val decisions = ConcurrentLinkedQueue<String>()
        /** Broadcasts issued by this node (originations + relays). */
        val transmissions = AtomicInteger(0)
        /** Consumer latch helper for tests. */
        val appLatch = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)

        fun start() = transport.start(this)

        fun stop() = transport.stop()

        fun expectApp(n: Int): CountDownLatch {
            val l = CountDownLatch(n)
            appLatch.set(l)
            return l
        }

        /** Origin-side flood start: mark seen BEFORE transmitting (own message). */
        fun originate(env: Envelope) {
            seen.see(SeenCache.Key(env.originId, env.msgId), RealTime.clock.nowMs())
            transmissions.incrementAndGet()
            transport.broadcast(env)
        }

        override fun onEnvelopeReceived(envelope: Envelope, from: NodeAddress) {
            val key = SeenCache.Key(envelope.originId, envelope.msgId)
            val alreadySeen = seen.seen(key, RealTime.clock.nowMs())
            wireSeen.add(from to envelope)
            if (!alreadySeen) {
                appDelivered.add(from to envelope)
                onAppEnvelope(envelope, from, this)
                appLatch.get()?.countDown()
            }
            val isOriginSelf = envelope.originId.contentEquals(originId)
            val d = flooder.onReceive(envelope, isOriginSelf, neighborCount = 1)
            when (d) {
                is Flooder.Decision.Rebroadcast -> if (!alreadySeen) {
                    pending[key] = scheduler.schedule(d.delayMs) {
                        pending.remove(key)
                        transmissions.incrementAndGet()
                        transport.broadcast(envelope)
                    }
                }
                is Flooder.Decision.Suppress -> pending.remove(key)?.cancel()
                is Flooder.Decision.Drop -> {}
            }
            decisions.add(d::class.simpleName!!)
        }
    }

    private fun startBridge(rangeMeters: Double, seed: Long = 7): TopologyEngine {
        val clock = ManualClock()
        val engine = TopologyEngine(seed = seed, rangeMeters = rangeMeters, lossProbability = 0.0)
        servers.add(SimBridgeServer(engine, port = 0, clock = clock))
        return engine
    }

    /** Connects a stack node for a static-positioned participant. */
    private fun connect(
        engine: TopologyEngine,
        server: SimBridgeServer,
        nodeId: String,
        originByte: Byte,
        position: Position,
        jitterSeed: Long,
        onApp: (Envelope, NodeAddress, BridgeNode) -> Unit = { _, _, _ -> },
    ): BridgeNode {
        engine.addStatic(nodeId, position)
        val originId = ByteArray(8) { originByte }
        val transport = BridgeMeshTransport(nodeId, "127.0.0.1", server.boundPort)
        val node = BridgeNode(nodeId, originId, transport, jitterSeed, onApp)
        node.start()
        nodes.add(node)
        awaitRegistered(server, nodeId)
        return node
    }

    private fun awaitRegistered(server: SimBridgeServer, nodeId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!server.registeredNodes().contains(nodeId)) {
            check(System.currentTimeMillis() < deadline) { "node $nodeId never registered" }
            Thread.sleep(10)
        }
    }

    private fun envelope(
        originId: ByteArray,
        type: MessageType,
        destination: Destination,
    ): Envelope = Validator.buildEnvelope(
        type = type,
        flags = Flags.ENCRYPTED or Flags.CRITICAL,
        destination = destination,
        payload = ByteArray(96) { (it * 3 + 1).toByte() },
        maxHops = 3,
        ttlSec = 120,
        identity = MeshCrypto.newIdentity(),
        originId = originId,
        originEpoch = 3,
        createdAtSec = 1_800_000_000,
    )

    @AfterTest
    fun tearDown() {
        nodes.forEach { it.stop() }
        servers.forEach { it.close() }
    }

    @Test
    fun `ride request floods two radio hops to an out-of-range driver and the quote returns`() {
        // Line topology, range 15 m: A(0) - B(10) in range, B(10) - C(20) in range,
        // A(0) - C(20) OUT of range (20 m > 15 m) -> C is reachable only via B.
        val engine = startBridge(rangeMeters = 15.0)
        val server = servers.first()
        val rider = connect(engine, server, "rider", 0x01, Position(0.0, 0.0), jitterSeed = 11)
        val relay = connect(engine, server, "relay", 0x02, Position(10.0, 0.0), jitterSeed = 22)
        val driver = connect(engine, server, "driver", 0x03, Position(20.0, 0.0), jitterSeed = 33)

        val request = envelope(rider.originId, MessageType.RIDE_REQUEST, Destination(DestKind.DRIVER_ANY))
        val quoteLatch = driver.expectApp(1)
        val riderQuoteLatch = rider.expectApp(1)

        rider.originate(request)
        assertTrue(quoteLatch.await(5, TimeUnit.SECONDS), "driver must receive the request via the relay hop")
        val atDriver = driver.appDelivered.first()
        assertEquals(NodeAddress("relay"), atDriver.first, "transport-level sender is the immediate radio peer")
        assertTrue(
            Codec.encode(request).contentEquals(Codec.encode(atDriver.second)),
            "flooded envelope must arrive byte-exact after one relay hop",
        )
        assertEquals(1, driver.appDelivered.size, "driver app must see the request exactly once")

        // The origin hears its own request relayed back by `relay` (radio echo) - the
        // app must NOT see it again, and no node may re-relay (loop prevention).
        val echoDeadline = System.currentTimeMillis() + 2_000
        while (rider.wireSeen.size < 1 && System.currentTimeMillis() < echoDeadline) Thread.sleep(10)
        assertTrue(rider.wireSeen.isNotEmpty(), "origin must hear the relayed copy over the bridge")
        assertEquals(0, rider.appDelivered.size, "seen-cache must dedup the echo at app level")

        // Response leg: the driver answers with an addressed QUOTE. Driver(20,0) and
        // rider(0,0) are 20 m apart - OUT of the 15 m range - so the QUOTE also floods
        // back through the relay (2 hops, the spec's forward+return shape).
        val quote = envelope(driver.originId, MessageType.QUOTE, Destination(DestKind.NODE, rider.originId))
        driver.originate(quote)
        assertTrue(riderQuoteLatch.await(5, TimeUnit.SECONDS), "rider must receive the driver's QUOTE")
        val atRider = rider.appDelivered.last()
        // Wire sender is the RELAY (immediate radio peer); authorship is proven by the
        // envelope's originId, not by the transport-level sender address.
        assertEquals(NodeAddress("relay"), atRider.first)
        assertTrue(atRider.second.originId.contentEquals(driver.originId), "QUOTE must carry the driver's originId")
        assertEquals(MessageType.QUOTE, atRider.second.type)
    }

    @Test
    fun `dense mesh dedups multi-path copies over the wire - app sees one per node`() {
        // Dense topology, range 25 m: A(0), B(10), C(20) are ALL pairwise in range.
        val engine = startBridge(rangeMeters = 25.0)
        val server = servers.first()
        val a = connect(engine, server, "A", 0x0A, Position(0.0, 0.0), jitterSeed = 101)
        val b = connect(engine, server, "B", 0x0B, Position(10.0, 0.0), jitterSeed = 202)
        val c = connect(engine, server, "C", 0x0C, Position(20.0, 0.0), jitterSeed = 303)

        val request = envelope(a.originId, MessageType.RIDE_REQUEST, Destination(DestKind.DRIVER_ANY))
        val bLatch = b.expectApp(1)
        val cLatch = c.expectApp(1)

        a.originate(request)
        assertTrue(bLatch.await(5, TimeUnit.SECONDS))
        assertTrue(cLatch.await(5, TimeUnit.SECONDS), "C must receive the request directly from A")

        // Wait out the jitter window so any pending rebroadcasts have fired.
        Thread.sleep(1_000)

        // C heard A's original AND B's relayed copy (two wire deliveries of one msgId)
        // but the app must have seen it exactly once.
        val cCopies = c.wireSeen.count { Codec.encode(it.second).contentEquals(Codec.encode(request)) }
        assertTrue(cCopies >= 2, "C must hear the multi-path copies at wire level, got $cCopies")
        assertEquals(1, c.appDelivered.size, "seen-cache must dedup multi-path copies at app level")
        assertEquals(1, b.appDelivered.size)

        // Loop prevention over real sockets: nobody ever re-relays a message it has
        // already seen - every node transmits exactly once (A origination + 2 relays).
        assertEquals(1, a.transmissions.get())
        assertEquals(1, b.transmissions.get(), "B relays once, never re-relays")
        assertEquals(1, c.transmissions.get(), "C relays once, never re-relays")
    }
}

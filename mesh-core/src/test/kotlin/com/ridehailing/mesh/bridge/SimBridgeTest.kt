package com.ridehailing.mesh.bridge

import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.Validator
import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.sim.Position
import com.ridehailing.mesh.transport.MeshTransport
import java.security.KeyPair
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1B-5 sim-bridge tests: the host bridge relays frames per the deterministic
 * topology engine (range, loss, scripted mobility) with byte-exact envelope passthrough
 * and BLE-like ack semantics. Real sockets over localhost; only the radio is simulated.
 */
class SimBridgeTest {

    private val servers = mutableListOf<SimBridgeServer>()
    private val transports = mutableListOf<BridgeMeshTransport>()

    private class RecordingListener : MeshTransport.Listener {
        val received = ConcurrentLinkedQueue<Pair<NodeAddress, Envelope>>()
        private val latches = mutableListOf<CountDownLatch>()
        private val lock = Any()

        fun expect(n: Int): CountDownLatch = synchronized(lock) {
            CountDownLatch(n).also { latches.add(it) }
        }

        override fun onEnvelopeReceived(envelope: Envelope, from: NodeAddress) {
            received.add(Pair(from, envelope))
            synchronized(lock) { latches.forEach { it.countDown() } }
        }
    }

    private class ManualClock : AtomicReference<Double>(0.0), () -> Double {
        override fun invoke(): Double = get()
        fun advanceTo(sec: Double) = set(sec)
    }

    private class Bridge(val server: SimBridgeServer, val clock: ManualClock, val engine: TopologyEngine)

    private fun startBridge(
        seed: Long = 1,
        lossProbability: Double = 0.0,
        rangeMeters: Double = 35.0,
    ): Bridge {
        val clock = ManualClock()
        val engine = TopologyEngine(seed = seed, rangeMeters = rangeMeters, lossProbability = lossProbability)
        val server = SimBridgeServer(engine, port = 0, clock = clock)
        servers.add(server)
        return Bridge(server, clock, engine)
    }

    /** Adds a static node to the engine and connects a transport for it. */
    private fun connectStatic(bridge: Bridge, nodeId: String, position: Position): Pair<BridgeMeshTransport, RecordingListener> {
        bridge.engine.addStatic(nodeId, position)
        val t = BridgeMeshTransport(nodeId, "127.0.0.1", bridge.server.boundPort)
        val listener = RecordingListener()
        t.start(listener)
        transports.add(t)
        awaitRegistered(bridge.server, nodeId)
        return Pair(t, listener)
    }

    private fun awaitRegistered(server: SimBridgeServer, nodeId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!server.registeredNodes().contains(nodeId)) {
            check(System.currentTimeMillis() < deadline) { "node $nodeId never registered" }
            Thread.sleep(10)
        }
    }

    private fun envelope(originIdByte: Byte, identity: KeyPair = MeshCrypto.newIdentity()): Envelope =
        Validator.buildEnvelope(
            type = MessageType.RIDE_REQUEST,
            flags = Flags.ENCRYPTED or Flags.CRITICAL,
            destination = Destination(DestKind.DRIVER_ID, ByteArray(8) { (it + 1).toByte() }),
            payload = ByteArray(64) { (it * 7).toByte() },
            maxHops = 3,
            ttlSec = 120,
            identity = identity,
            originId = ByteArray(8) { originIdByte },
            originEpoch = 42,
            createdAtSec = 1_800_000_000,
        )

    @AfterTest
    fun tearDown() {
        transports.forEach { it.stop() }
        servers.forEach { it.close() }
    }

    @Test
    fun `broadcast reaches in-range peers only and the envelope is byte-exact`() {
        val bridge = startBridge()
        val (a, la) = connectStatic(bridge, "A", Position(0.0, 0.0))
        val (_, lb) = connectStatic(bridge, "B", Position(10.0, 0.0))
        val (_, lc) = connectStatic(bridge, "C", Position(1000.0, 0.0))
        val latch = lb.expect(1)
        val env = envelope(0x01)

        a.broadcast(env)
        assertTrue(latch.await(5, TimeUnit.SECONDS), "in-range B must receive the broadcast")
        assertEquals(1, lb.received.size)
        assertEquals(NodeAddress("A"), lb.received.first().first)
        // Kotlin data-class equality compares ByteArrays by REFERENCE - assert the wire
        // round trip instead: re-encoding the received envelope must be byte-identical.
        assertTrue(
            Codec.encode(env).contentEquals(Codec.encode(lb.received.first().second)),
            "envelope must arrive byte-identical",
        )
        assertEquals(0, lc.received.size, "out-of-range C must receive nothing")
        assertEquals(0, la.received.size, "sender never hears its own transmission")
    }

    @Test
    fun `unicast acks in range and nacks out of range or unknown`() {
        val bridge = startBridge()
        val (a, _) = connectStatic(bridge, "A", Position(0.0, 0.0))
        val (_, lb) = connectStatic(bridge, "B", Position(10.0, 0.0))
        val bLatch = lb.expect(1)

        assertTrue(a.sendTo(NodeAddress("B"), envelope(0x02)), "in-range unicast must be acked")
        assertTrue(bLatch.await(5, TimeUnit.SECONDS), "B must receive the unicast")
        assertFalse(a.sendTo(NodeAddress("C"), envelope(0x02)), "out-of-range unicast must nack")
        assertFalse(a.sendTo(NodeAddress("ghost"), envelope(0x02)), "unknown nextHop must nack")
    }

    @Test
    fun `mobility brings C into range and delivery starts at the new bridge time`() {
        val bridge = startBridge()
        val (a, _) = connectStatic(bridge, "A", Position(0.0, 0.0))
        // C walks from far away to A's side between t=0 and t=10.
        bridge.engine.addTrack("C", listOf(
            TopologyEngine.Waypoint(0.0, 1000.0, 0.0),
            TopologyEngine.Waypoint(10.0, 5.0, 0.0),
        ))
        val tC = BridgeMeshTransport("C", "127.0.0.1", bridge.server.boundPort)
        val lc = RecordingListener()
        tC.start(lc)
        transports.add(tC)
        awaitRegistered(bridge.server, "C")

        val outOfRange = lc.expect(1)
        a.broadcast(envelope(0x03))
        assertFalse(outOfRange.await(1, TimeUnit.SECONDS), "C at t=0 is out of range")
        assertEquals(0, lc.received.size)

        bridge.clock.advanceTo(10.0)
        val inRange = lc.expect(1)
        a.broadcast(envelope(0x04))
        assertTrue(inRange.await(5, TimeUnit.SECONDS), "C at t=10 is in range")
        assertTrue(lc.received.any { it.first == NodeAddress("A") })
    }

    @Test
    fun `total loss drops everything - broadcast silently, unicast nacks`() {
        val bridge = startBridge(lossProbability = 1.0)
        val (a, _) = connectStatic(bridge, "A", Position(0.0, 0.0))
        val (_, lb) = connectStatic(bridge, "B", Position(10.0, 0.0))
        val latch = lb.expect(1)

        a.broadcast(envelope(0x05))
        assertFalse(latch.await(1, TimeUnit.SECONDS), "loss=1.0 must drop broadcasts")
        assertEquals(0, lb.received.size)
        assertFalse(a.sendTo(NodeAddress("B"), envelope(0x05)), "loss=1.0 must nack unicasts (no ack)")
    }

    @Test
    fun `reconnect with the same nodeId replaces the stale session and delivery resumes`() {
        val bridge = startBridge()
        val (a, _) = connectStatic(bridge, "A", Position(0.0, 0.0))
        val (bFirst, lFirst) = connectStatic(bridge, "B", Position(10.0, 0.0))
        bFirst.stop()
        // The app process reconnects with the same identity.
        val (bSecond, lSecond) = connectStatic(bridge, "B", Position(10.0, 0.0))
        assertEquals(2, bridge.server.registeredNodes().size, "A + the replaced B (stale session swapped, not duplicated)")

        val latch = lSecond.expect(1)
        a.broadcast(envelope(0x06))
        assertTrue(latch.await(5, TimeUnit.SECONDS), "delivery must resume on the new session")
        assertEquals(0, lFirst.received.size, "the closed session must stay silent")
        bSecond.stop()
    }

    @Test
    fun `topology engine is deterministic for a fixed seed and range checks consume no rng`() {
        fun build(): TopologyEngine {
            val e = TopologyEngine(seed = 99, rangeMeters = 35.0, lossProbability = 0.5)
            e.addTrack("A", listOf(TopologyEngine.Waypoint(0.0, 0.0, 0.0), TopologyEngine.Waypoint(10.0, 20.0, 0.0)))
            e.addStatic("B", Position(10.0, 0.0))
            e.addStatic("C", Position(1000.0, 0.0))
            return e
        }
        val e1 = build()
        val e2 = build()
        // Same seed, same schedule -> identical first 50 rolls.
        val s1a = (0 until 50).map { e1.transmissionDelivers("A", "B", atSec = it % 11.0) }
        val s2a = (0 until 50).map { e2.transmissionDelivers("A", "B", atSec = it % 11.0) }
        assertEquals(s1a, s2a, "identical seeds must produce identical loss sequences")
        // e1 interleaves 10 PURE out-of-range checks (asserted non-delivery).
        // e3 is the control: same seed, NO interleave. If range checks consume no rng,
        // e1's continuation must equal e3's continuation exactly.
        val e3 = build()
        val s3a = (0 until 50).map { e3.transmissionDelivers("A", "B", atSec = it % 11.0) }
        assertEquals(s1a, s3a, "control alignment")
        repeat(10) {
            assertFalse(e1.transmissionDelivers("A", "C", atSec = 5.0))
        }
        val s1b = (0 until 50).map { e1.transmissionDelivers("A", "B", atSec = it % 11.0) }
        val s3b = (0 until 50).map { e3.transmissionDelivers("A", "B", atSec = it % 11.0) }
        assertEquals(s1b, s3b, "out-of-range checks must not consume rng")
    }
}

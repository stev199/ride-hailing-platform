package com.ridehailing.mesh.bridge

import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DecodeResult
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.transport.MeshTransport
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 4 prep (P1B-5): the client-side half of the host sim bridge - a REAL
 * [MeshTransport] implementation the app code runs against on emulators (GATE 4),
 * with only the radio (neighborhood, loss, mobility) living in the host
 * [SimBridgeServer].
 *
 * - `broadcast(envelope)` sends one BCAST frame; the HOST replicates to every in-range
 *   neighbor (the client never knows the neighborhood - same as BLE advertising).
 * - `sendTo(nextHop, envelope)` sends one UNICAST frame and blocks for the bridge's
 *   ACK/NACK (BLE GATT write-with-response semantics; the return value = "the hop was
 *   acked" exactly like [MeshTransport.sendTo] documents).
 *
 * The socket is created through [socketFactory] so Android can inject adb-reverse /
 * emulator-special-loopback sockets without this class changing.
 */
class BridgeMeshTransport(
    private val nodeId: String,
    private val host: String,
    private val port: Int,
    private val ackTimeoutMs: Long = 5_000,
    private val socketFactory: () -> Socket = { Socket(host, port) },
) : MeshTransport {

    private val listenerRef = AtomicReference<MeshTransport.Listener?>(null)
    private val pendingAcks = ConcurrentHashMap<Int, CountDownLatch>()
    private val ackResults = ConcurrentHashMap<Int, Boolean>()
    private val seq = AtomicInteger(0)
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var reader: Thread? = null
    private val startLock = Any()
    @Volatile private var started = false

    override fun start(listener: MeshTransport.Listener) {
        synchronized(startLock) {
            check(!started) { "BridgeMeshTransport already started" }
            listenerRef.set(listener)
            val s = socketFactory()
            s.tcpNoDelay = true
            socket = s
            val o = DataOutputStream(s.getOutputStream())
            out = o
            SimBridgeFrames.writeFrame(o, SimBridgeFrames.TYPE_HELLO, SimBridgeFrames.helloBody(nodeId))
            started = true
            reader = Thread({ readLoop(s) }, "bridge-transport-$nodeId").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun stop() {
        synchronized(startLock) {
            started = false
            try { socket?.close() } catch (_: Exception) {}
            reader = null
        }
    }

    override fun broadcast(envelope: Envelope) {
        check(started) { "transport not started" }
        SimBridgeFrames.writeFrame(out!!, SimBridgeFrames.TYPE_BCAST, Codec.encode(envelope))
    }

    override fun sendTo(nextHop: NodeAddress, envelope: Envelope): Boolean {
        check(started) { "transport not started" }
        val s = seq.incrementAndGet()
        val latch = CountDownLatch(1)
        pendingAcks[s] = latch
        try {
            SimBridgeFrames.writeFrame(out!!, SimBridgeFrames.TYPE_UNICAST, SimBridgeFrames.unicastBody(s, nextHop.value, Codec.encode(envelope)))
            if (!latch.await(ackTimeoutMs, TimeUnit.MILLISECONDS)) return false
            return ackResults.remove(s) ?: false
        } finally {
            pendingAcks.remove(s)
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream())
            while (started) {
                val frame = SimBridgeFrames.readFrame(input) ?: break
                when (frame.type) {
                    SimBridgeFrames.TYPE_ACK -> {
                        val id = SimBridgeFrames.parseSeq(frame.body)
                        ackResults[id] = true
                        pendingAcks[id]?.countDown()
                    }
                    SimBridgeFrames.TYPE_NACK -> {
                        val (id, _) = SimBridgeFrames.parseNack(frame.body)
                        ackResults[id] = false
                        pendingAcks[id]?.countDown()
                    }
                    SimBridgeFrames.TYPE_DELIVER -> {
                        val d = SimBridgeFrames.parseDeliver(frame.body)
                        val decoded = when (val r = Codec.decode(d.envelope)) {
                            is DecodeResult.Ok -> r.envelope
                            else -> null // malformed on the wire = radio-level corruption; drop
                        }
                        if (decoded != null) {
                            listenerRef.get()?.onEnvelopeReceived(decoded, NodeAddress(d.from))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // socket closed by stop() or host disconnect: pending unicast senders time out
        } finally {
            started = false
            pendingAcks.keys.forEach { pendingAcks[it]?.countDown() }
        }
    }
}

package com.ridehailing.mesh.bridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 4 prep (P1B-5): the host sim-bridge server (GATE 4's "host sim bridge").
 *
 * One TCP port; every emulator app connects with [BridgeMeshTransport] and registers
 * its nodeId via HELLO. The server relays frames per [TopologyEngine]:
 * - BCAST: one DELIVER to every OTHER in-range registered node (per-neighbor radio).
 * - UNICAST: DELIVER to the named nextHop when in range, then ACK to the sender;
 *   NACK(reason=unreachable) otherwise. sendTo() ack semantics match BLE GATT
 *   write-with-response ("the hop was acked").
 *
 * The engine clock is injectable: tests advance it deterministically; the host runner
 * binds it to elapsed wall time. Deterministic topology/loss (seeded) + opaque bytes
 * keep the bridge's trust model identical to the radio's.
 *
 * Optionally logs one JSONL line per delivery/drop for GATE 4 evidence (file sink).
 */
class SimBridgeServer(
    private val engine: TopologyEngine,
    private val port: Int = 0,
    /** Host-side clock in seconds (bridge time). Tests advance it manually. */
    private val clock: () -> Double,
    private val eventSink: ((String) -> Unit)? = null,
) {
    private val serverSocket = ServerSocket(port)
    private val running = AtomicBoolean(true)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val acceptThread: Thread

    val boundPort: Int get() = serverSocket.localPort

    init {
        acceptThread = Thread({ acceptLoop() }, "sim-bridge-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun registeredNodes(): Set<String> = sessions.keys.toSet()

    fun close() {
        running.set(false)
        sessions.values.forEach { it.close() }
        serverSocket.close()
    }

    private fun log(line: String) {
        eventSink?.invoke(line)
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                return // closed
            }
            Thread({ Session(socket).run() }, "sim-bridge-session").apply { isDaemon = true }.start()
        }
    }

    private inner class Session(private val socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val nodeIdRef = AtomicReference<String?>(null)
        private val writeLock = Any()

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }

        /** Serialized write (one DELIVER at a time per session). Returns false on IO failure.
         * NOTE: the topology/loss roll happens in the CALLER - this method only writes. */
        fun writeDeliver(from: String, envelope: ByteArray): Boolean {
            val nodeId = nodeIdRef.get() ?: return false
            return try {
                synchronized(writeLock) {
                    SimBridgeFrames.writeFrame(out, SimBridgeFrames.TYPE_DELIVER, SimBridgeFrames.deliverBody(from, envelope))
                }
                log("""{"event":"deliver","from":"$from","to":"$nodeId","bytes":${envelope.size},"tSec":${clock()}}""")
                true
            } catch (_: Exception) {
                false
            }
        }

        fun run() {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (running.get()) {
                    val frame = SimBridgeFrames.readFrame(input) ?: break
                    when (frame.type) {
                        SimBridgeFrames.TYPE_HELLO -> {
                            val nodeId = SimBridgeFrames.parseHello(frame.body)
                            sessions[nodeId]?.close() // duplicate id replaces the stale session
                            nodeIdRef.set(nodeId)
                            sessions[nodeId] = this
                            synchronized(writeLock) {
                                SimBridgeFrames.writeFrame(out, SimBridgeFrames.TYPE_HELLO_OK, ByteArray(0))
                            }
                            log("""{"event":"hello","node":"$nodeId","tSec":${clock()}}""")
                        }
                        SimBridgeFrames.TYPE_BCAST -> {
                            val from = nodeIdRef.get() ?: continue
                            val atSec = clock()
                            val targets = engine.neighborsOf(from, atSec, sessions.keys)
                            for (t in targets) {
                                val s = sessions[t] ?: continue
                                if (engine.transmissionDelivers(from, t, atSec)) {
                                    try {
                                        synchronized(s.writeLock) {
                                            SimBridgeFrames.writeFrame(s.out, SimBridgeFrames.TYPE_DELIVER, SimBridgeFrames.deliverBody(from, frame.body))
                                        }
                                        log("""{"event":"deliver","from":"$from","to":"$t","bytes":${frame.body.size},"tSec":$atSec}""")
                                    } catch (_: Exception) {
                                        log("""{"event":"drop-disconnected","from":"$from","to":"$t","tSec":$atSec}""")
                                    }
                                } else {
                                    log("""{"event":"drop-loss","from":"$from","to":"$t","tSec":$atSec}""")
                                }
                            }
                        }
                        SimBridgeFrames.TYPE_UNICAST -> {
                            val from = nodeIdRef.get() ?: continue
                            val u = SimBridgeFrames.parseUnicast(frame.body)
                            val target = sessions[u.nextHop]
                            // ONE loss roll per transmission attempt (D69 honesty: the dice
                            // decide delivery, not the write path).
                            val reachable = target != null && engine.transmissionDelivers(from, u.nextHop, clock())
                            val ok = reachable && target!!.writeDeliver(from, u.envelope)
                            try {
                                synchronized(writeLock) {
                                    if (ok) {
                                        SimBridgeFrames.writeFrame(out, SimBridgeFrames.TYPE_ACK, SimBridgeFrames.ackBody(u.seq))
                                    } else {
                                        SimBridgeFrames.writeFrame(out, SimBridgeFrames.TYPE_NACK, SimBridgeFrames.nackBody(u.seq, SimBridgeFrames.NACK_UNREACHABLE))
                                    }
                                }
                            } catch (_: Exception) {
                                return // sender went away
                            }
                        }
                        else -> log("""{"event":"unknown-frame","type":${frame.type}}""")
                    }
                }
            } catch (_: Exception) {
                // socket reset / EOF -> unregister below
            } finally {
                nodeIdRef.get()?.let { id ->
                    if (sessions[id] == this) sessions.remove(id)
                    log("""{"event":"bye","node":"$id","tSec":${clock()}}""")
                }
                close()
            }
        }
    }
}

package com.ridehailing.mesh.transport

import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.routing.NodeAddress

/**
 * Transport abstraction (Part C module layout: transport = `MeshTransport` +
 * `SimulatedMeshTransport`). Stage 4 provides `BleMeshTransport` on Android; the
 * Stage 3 simulator provides the in-process, virtual-time implementation.
 *
 * BLE is connection-oriented: there is no omnidirectional broadcast across GATT
 * connections, so "broadcast" means one send per neighbor - which is exactly what
 * the per-transmission accounting of the scenario suite needs.
 */
interface MeshTransport {

    interface Listener {
        /** A fully received envelope from neighbor [from] (retries from the same sender are suppressed upstream). */
        fun onEnvelopeReceived(envelope: Envelope, from: NodeAddress)
    }

    fun start(listener: Listener)

    fun stop()

    /** Sends [envelope] once to every current neighbor (per-neighbor transmission). */
    fun broadcast(envelope: Envelope)

    /** One source-routed hop towards [nextHop]. Returns true when the hop was acked. */
    fun sendTo(nextHop: NodeAddress, envelope: Envelope): Boolean
}

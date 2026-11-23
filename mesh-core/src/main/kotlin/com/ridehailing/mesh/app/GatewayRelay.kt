package com.ridehailing.mesh.app

import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DecodeResult
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType

/**
 * GATE 2 close-out (Part C Stage 2, spec "Return leg" + Stage 5 gateway rules
 * pulled forward into mesh-core primitives): the internet shortcut and the
 * opaque gateway relay.
 *
 * Internet shortcut (spec): "if any node on the path (or the rider) has
 * internet, the backend may return the response through that gateway
 * (GATEWAY_RELAY) and the mesh covers only the remaining hops; prefer fewest
 * mesh hops when scores are within 10%."
 *
 * Gateway rules (spec Stage 5, enforced by construction here): a gateway accepts
 * GATEWAY_RELAY / RIDE_REQUEST envelopes, forwards them OPAQUELY (original
 * signature intact) to the backend, and returns server responses into the mesh
 * along the best known path. A gateway never decrypts payloads and never learns
 * user identity - [GatewayLink] carries opaque byte arrays only.
 *
 * GATEWAY_RELAY payload layout (gateway -> rider, response carry):
 *   innerLen u16 | inner envelope bytes (a QUOTE / TRIP_UPDATE built by the
 *   backend, origin-signed by the backend mesh identity) | gwTag 8B
 *
 * The inner envelope is NOT decrypted or re-signed by the gateway: it re-injects
 * the backend's envelope bytes verbatim under its own GATEWAY_RELAY wrapper.
 */

/** GATEWAY_ANNOUNCE payload: capabilities + time hint. First 4 bytes stay the backend-signed clock seconds (existing clock-sync contract). */
object GatewayAnnounceCodec {

    const val CAP_INTERNET: Int = 0x01

    data class Announce(
        val clockSec: Int,
        val capabilities: Int,
        val beaconIntervalSec: Int,
    )

    fun encode(announce: Announce): ByteArray {
        val s = announce.clockSec
        return byteArrayOf(
            ((s ushr 24) and 0xFF).toByte(), ((s ushr 16) and 0xFF).toByte(),
            ((s ushr 8) and 0xFF).toByte(), (s and 0xFF).toByte(),
            (announce.capabilities and 0xFF).toByte(),
            (announce.beaconIntervalSec and 0xFF).toByte(),
        )
    }

    /** Never throws (payload may be hostile). */
    fun decode(payload: ByteArray): Announce? {
        if (payload.size < 6) return null
        val clockSec = ((payload[0].toInt() and 0xFF) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        return Announce(clockSec, payload[4].toInt() and 0xFF, payload[5].toInt() and 0xFF)
    }
}

/**
 * The gateway's "HTTPS/WSS" - opaque bytes in, opaque response bytes out.
 * Production (Stage 5) implements this over an HTTPS client; the simulator
 * (Stage 3, GATE 2 scenarios) implements it against an in-process fake backend.
 */
interface GatewayLink {
    /**
     * Forwards [requestEnvelopeBytes] (encoded envelope, payload untouched -
     * the gateway MUST NOT parse, decrypt, or re-sign it) with the gateway's
     * identity for backend accounting. Returns the backend's response envelope
     * bytes, or null when the backend rejected (revoked origin, replay, ...).
     */
    fun forward(requestEnvelopeBytes: ByteArray, gatewayId: ByteArray): ByteArray?
}

sealed class GatewayRelayResult {
    /** Forwarded to the backend; response (if any) was re-injected into the mesh. */
    data object Forwarded : GatewayRelayResult()
    /** Not a relayable envelope (wrong type/destination) - the mesh pipeline keeps handling it. */
    data object NotRelayable : GatewayRelayResult()
    /** Backend returned nothing (rejection); nothing is injected. */
    data object BackendRejected : GatewayRelayResult()
}

/**
 * Gateway-side handler. [forwardIntoMesh] re-injects the backend's response
 * envelope: the CALLER decides how (source route along the best known path,
 * scoped flood otherwise) - the service only wraps it in a GATEWAY_RELAY
 * envelope addressed to the response's destination node.
 */
class GatewayRelayService(
    private val gatewayId: ByteArray,
    val identity: java.security.KeyPair,
    private val link: GatewayLink,
    private val forwardIntoMesh: (Envelope) -> Unit,
    private val clock: () -> Int,
) {

    /** Handles one received envelope; call after mesh validation, before flooding. */
    fun onEnvelope(env: Envelope): GatewayRelayResult {
        val relayable = env.type == MessageType.GATEWAY_RELAY || env.type == MessageType.RIDE_REQUEST
        val forGateway = env.destination.kind == DestKind.GATEWAY_ANY ||
            (env.destination.kind == DestKind.NODE && env.destination.id?.contentEquals(gatewayId) == true)
        if (!relayable || !forGateway) return GatewayRelayResult.NotRelayable

        // OPAQUE forward: original bytes with the original signature - the gateway
        // never decrypts payloads and never learns user identity (spec Stage 5).
        val responseBytes = link.forward(Codec.encode(env), gatewayId) ?: return GatewayRelayResult.BackendRejected

        // Wrap the backend response for the remaining mesh hops. The backend built
        // the inner envelope already addressed to the rider; the gateway copies it
        // verbatim (byte-for-byte) into the GATEWAY_RELAY payload.
        val inner = Codec.decode(responseBytes)
        if (inner !is DecodeResult.Ok) return GatewayRelayResult.BackendRejected
        val payload = ByteArray(2 + responseBytes.size + 8)
        payload[0] = ((responseBytes.size ushr 8) and 0xFF).toByte()
        payload[1] = (responseBytes.size and 0xFF).toByte()
        responseBytes.copyInto(payload, 2)
        MeshCrypto.hmacSha256(gatewayId, responseBytes).copyOf(8).copyInto(payload, 2 + responseBytes.size)
        val wrapper = com.ridehailing.mesh.protocol.Validator.buildEnvelope(
            type = MessageType.GATEWAY_RELAY,
            flags = Flags.CRITICAL,
            destination = inner.envelope.destination,
            payload = payload,
            maxHops = inner.envelope.maxHops,
            ttlSec = inner.envelope.ttlSec,
            identity = identity,
            originId = gatewayId,
            originEpoch = 1,
            createdAtSec = clock(),
        )
        forwardIntoMesh(wrapper)
        return GatewayRelayResult.Forwarded
    }

    /** Rider-side unwrap. Returns the inner envelope or null (truncated/tampered gwTag). */
    fun unwrap(gatewayRelayPayload: ByteArray): Envelope? {
        if (gatewayRelayPayload.size < 2 + 64 + 8) return null
        val innerLen = ((gatewayRelayPayload[0].toInt() and 0xFF) shl 8) or (gatewayRelayPayload[1].toInt() and 0xFF)
        if (gatewayRelayPayload.size < 2 + innerLen + 8) return null
        val innerBytes = gatewayRelayPayload.copyOfRange(2, 2 + innerLen)
        val tag = gatewayRelayPayload.copyOfRange(2 + innerLen, 2 + innerLen + 8)
        if (!MeshCrypto.hmacSha256(gatewayId, innerBytes).copyOf(8).contentEquals(tag)) return null
        return when (val r = Codec.decode(innerBytes)) {
            is DecodeResult.Ok -> r.envelope
            else -> null
        }
    }
}

/**
 * Internet-shortcut path preference (spec: "prefer fewest mesh hops when scores
 * are within 10%"). The gateway path wins when its end-to-end cost is at most
 * 10% above the pure-mesh cost AND it needs fewer mesh hops. Returns true when
 * the GATEWAY path should be used.
 */
object InternetShortcut {

    const val SCORE_TOLERANCE: Double = 0.10

    fun preferGateway(
        meshPathCost: Double,
        meshPathHops: Int,
        gatewayPathCost: Double,
        gatewayPathHops: Int,
        gatewayHasInternet: Boolean,
    ): Boolean {
        if (!gatewayHasInternet) return false
        if (gatewayPathHops >= meshPathHops) return false
        return gatewayPathCost <= meshPathCost * (1.0 + SCORE_TOLERANCE)
    }
}

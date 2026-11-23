package com.ridehailing.mesh.sim

import com.ridehailing.mesh.app.BeaconLedger
import com.ridehailing.mesh.app.BeaconScheduler
import com.ridehailing.mesh.app.DriverBeaconCredentials
import com.ridehailing.mesh.app.DriverCredentials
import com.ridehailing.mesh.app.GatewayRelayService
import com.ridehailing.mesh.app.GatewayResponse
import com.ridehailing.mesh.app.PresenceCodec
import com.ridehailing.mesh.app.ProfileSnippet
import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DecodeResult
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.Validator

/**
 * GATE 2 close-out sim wiring (scenarios 16-19): an in-process fake backend that
 * stands in for the Stage 5 `mesh-gateway` service (signature checks, decrypt,
 * quote) behind the gateway's [GatewayLink] "HTTPS/WSS", plus the world
 * extensions that set up beacons, presence and gateway nodes.
 *
 * The backend NEVER talks to the mesh directly - it only answers gateway
 * forwards, mirroring the production topology where the internet shortcut is
 * always gateway-mediated.
 */
class SimulatedBackendLink(private val world: SimWorld) : com.ridehailing.mesh.app.GatewayLink {

    val ed25519 = MeshCrypto.newIdentity()
    val ed25519Public = ed25519.public
    val x25519 = MeshCrypto.newX25519KeyPair()
    val x25519Public = x25519.public
    val originId: ByteArray = MeshCrypto.sha256("backend-mesh-gateway".toByteArray()).copyOf(8)
    val originIdHex = world.hex(originId)

    // backend-side observability (admin-only tables in production)
    val receivedPresence = mutableListOf<PresenceCodec.Presence>()
    var openedPresence = 0L
    var openedRequests = 0L
    var rejectedRevoked = 0L
    val forwarded = mutableListOf<Pair<ByteArray, ByteArray>>() // request bytes -> gatewayId
    val injectedWrappers = mutableListOf<Envelope>()

    init {
        // The backend mesh identity is pinned like a node key: riders verify the
        // inner QUOTE signature after unwrapping (trust anchor - spec Stage 1).
        world.keys[originIdHex] = ed25519.public
    }

    override fun forward(requestEnvelopeBytes: ByteArray, gatewayId: ByteArray): GatewayResponse {
        forwarded.add(requestEnvelopeBytes to gatewayId)
        val decoded = Codec.decode(requestEnvelopeBytes)
        if (decoded !is DecodeResult.Ok) return GatewayResponse.Rejected
        val env = decoded.envelope
        // Backend-side revocation check (mirrors world.revokedOrigins - the backend
        // is the trust anchor that feeds that list in production).
        if (world.revokedOrigins.contains(world.hex(env.originId))) {
            rejectedRevoked++
            return GatewayResponse.Rejected
        }
        return when (env.type) {
            MessageType.PRESENCE -> {
                val opened = PresenceCodec.open(env.payload, x25519.private)
                if (opened == null) {
                    GatewayResponse.Rejected
                } else {
                    receivedPresence.add(opened)
                    openedPresence++
                    GatewayResponse.NoResponse
                }
            }
            MessageType.RIDE_REQUEST -> {
                val opened = MeshCrypto.open(x25519.private, x25519.public, env.payload)
                    ?: return GatewayResponse.Rejected
                val tag = String(opened, Charsets.UTF_8).removePrefix("request|")
                openedRequests++
                val rider = world.nodeByOriginId(world.hex(env.originId))
                    ?: return GatewayResponse.Rejected
                // The authoritative quote (spec Stage 5): signed by the backend mesh
                // identity, addressed to the rider's rotating originId.
                val quote = Validator.buildEnvelope(
                    type = MessageType.QUOTE,
                    flags = Flags.CRITICAL,
                    destination = Destination(DestKind.NODE, rider.originId),
                    payload = "quote|$tag".toByteArray(),
                    maxHops = env.maxHops,
                    ttlSec = 30,
                    identity = ed25519,
                    originId = originId,
                    originEpoch = 1,
                    createdAtSec = (world.engine.clock.nowMs() / 1000).toInt(),
                )
                GatewayResponse.Payload(Codec.encode(quote))
            }
            else -> GatewayResponse.Rejected
        }
    }
}

/** Attaches the fake backend to the world (one per world; gateways forward to it). */
fun SimWorld.attachBackend(): SimulatedBackendLink {
    backendLink?.let { return it }
    val link = SimulatedBackendLink(this)
    backendLink = link
    return link
}

/**
 * Wires a GATEWAY node to the backend: the gateway runs the opaque relay service
 * (never decrypts, never re-signs) and re-injects backend responses as flooded
 * GATEWAY_RELAY wrappers (the sim models "best known path" as a scoped flood -
 * delivery to the addressed rider completes via the node-destined envelope).
 */
fun SimWorld.wireGateway(name: String) {
    val gw = node(name)
    val backend = backendLink ?: error("attachBackend() before wireGateway()")
    gw.gatewayService = GatewayRelayService(
        gatewayId = gw.originId,
        identity = gw.identity,
        link = backend,
        forwardIntoMesh = { wrapper ->
            backend.injectedWrappers.add(wrapper)
            gw.startFlood(SimWorld.Flight(wrapper, emptyList(), gw.id))
        },
        clock = { gw.localNowSec() },
    )
}

/**
 * Sets up GATE 2 app behavior on a node: drivers get certified beacon
 * credentials + the adaptive scheduler; riders get a beacon ledger. The
 * scheduler reads the WORLD clock (beacon pacing does not model clock skew).
 */
fun SimWorld.setupBeacons(name: String, backend: SimulatedBackendLink, driverId: ByteArray, nowSec: Int) {
    val n = node(name)
    n.beaconScheduler = BeaconScheduler(
        { engine.clock.nowMs() },
        baseIntervalSec = n.meshConfig.beaconIntervalSec,
    )
    when (n.role) {
        SimRole.DRIVER -> {
            val pKey = MeshCrypto.newIdentity()
            val cert = DriverCredentials.issueCertificate(driverId, pKey, nowSec, nowSec + 1_000_000, backend.ed25519)
            val snippet = DriverCredentials.signSnippet(
                ProfileSnippet(
                    driverId, "Driver-${n.name}", 46, "vehicle",
                    MeshCrypto.sha256("photo|${n.name}".toByteArray()).copyOf(8), nowSec, ByteArray(64),
                ),
                pKey,
            )
            n.beaconCredentials = DriverBeaconCredentials(pKey, driverId, cert, snippet)
        }
        SimRole.RIDER -> n.beaconLedger = BeaconLedger({ engine.clock.nowMs() })
        else -> {}
    }
}

/** Offline rider presence emission (PRESENCE, sealed to the backend, dest GATEWAY_ANY). */
fun SimWorld.sendPresence(name: String) {
    val rider = node(name)
    val backend = backendLink ?: return
    val sealed = PresenceCodec.seal(
        rider.originId, rider.position.x, rider.position.y,
        rider.localNowSec(), backend.x25519Public,
    )
    metrics.presenceSent++
    presenceSealedSent.add(sealed)
    val env = Validator.buildEnvelope(
        type = MessageType.PRESENCE,
        flags = Flags.ENCRYPTED,
        destination = Destination(DestKind.GATEWAY_ANY),
        payload = sealed,
        maxHops = 3,
        ttlSec = 120,
        identity = rider.identity,
        originId = rider.originId,
        originEpoch = 1,
        createdAtSec = rider.localNowSec(),
    )
    rider.startFlood(SimWorld.Flight(env, emptyList(), rider.id))
}

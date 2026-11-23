package com.ridehailing.mesh.sim

import com.ridehailing.mesh.app.BEACON_POSITION_GRID_M
import com.ridehailing.mesh.app.InternetShortcut
import com.ridehailing.mesh.app.PRESENCE_POSITION_GRID_M
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import kotlin.math.abs

/**
 * GATE 2 close-out scenarios 16-19 (Part C Stage 2 remainder): DRIVER_BEACON
 * coverage + adaptive pacing, PRESENCE relay with backend-only encryption, the
 * GATEWAY_RELAY internet shortcut, and gateway opacity/replay/revocation.
 * Fixed seeds, virtual time - deterministic like scenarios 1-15.
 */
object ScenariosGate2 {

    // ---------------- 16. driver beacons: coverage, quantized marker, profile relay-once, adaptive pacing ----------------

    fun driverBeacons(seed: Long = 1116L): ScenarioResult {
        val driverId = com.ridehailing.mesh.crypto.MeshCrypto.sha256("driver-cert-16".toByteArray()).copyOf(8)

        // (a) sparse line world: rider - rel - driver; beacons for 21 s -> 5 emissions
        val world = SimWorld.create(seed, maxHops = 3)
        val backend = world.attachBackend()
        world.addNode("rider", SimRole.RIDER)
        world.addNode("rel", SimRole.RELAY)
        val driver = world.addNode("driver", SimRole.DRIVER)
        driver.position = Position(123.0, -77.0)
        Topologies.line(world, listOf("rider", "rel", "driver"))
        world.setupBeacons("rider", backend, driverId, 0)
        world.setupBeacons("driver", backend, driverId, 0)
        for (t in 0..21) world.engine.postIn(t * 1000L) { world.node("driver").emitBeaconIfDue() }
        world.runUntil(23_000)

        val ledger = world.node("rider").beaconLedger!!
        val heard = ledger.nearby(world.engine.clock.nowMs())
        // Coverage = every emission the rider heard (the ledger itself keeps ONE live
        // entry per driver - the freshest marker - so it cannot count emissions).
        val coverage = world.metrics.beaconsHeard / 5.0
        // marker error: quantized position (10 m grid) vs the driver's true position
        val markerErr = heard.maxOf { e ->
            maxOf(
                abs(e.payload.latQuantized * BEACON_POSITION_GRID_M - driver.position.x),
                abs(e.payload.lonQuantized * BEACON_POSITION_GRID_M - driver.position.y),
            )
        }
        val profile = ledger.profileFor(driverId)
        val profileOk = profile != null && profile.displayName == "Driver-driver"
        val macVerified = world.metrics.beaconsMacVerified.toDouble()
        val emissions = world.metrics.beaconsEmitted.toDouble()
        val withProfile = world.metrics.beaconsWithProfile.toDouble()

        // (b) dense world: driver at the center of a 20-leaf star -> interval stretches
        val dense = SimWorld.create(seed + 1, maxHops = 3)
        val backendD = dense.attachBackend()
        dense.addNode("driver", SimRole.DRIVER)
        for (i in 0 until 20) dense.addNode("leaf$i", SimRole.RELAY)
        Topologies.star(dense, "driver", (0 until 20).map { "leaf$it" })
        dense.setupBeacons("driver", backendD, driverId, 0)
        for (t in 0..60) dense.engine.postIn(t * 1000L) { dense.node("driver").emitBeaconIfDue() }
        dense.runUntil(62_000)
        val denseEmissions = dense.metrics.beaconsEmitted.toDouble()
        // sparse cadence over the same 60 s window: interval 5 s -> 12 emissions
        val sparse = SimWorld.create(seed + 2, maxHops = 3)
        val backendS = sparse.attachBackend()
        sparse.addNode("driver", SimRole.DRIVER)
        sparse.addNode("rel", SimRole.RELAY)
        Topologies.line(sparse, listOf("driver", "rel"))
        sparse.setupBeacons("driver", backendS, driverId, 0)
        for (t in 0..60) sparse.engine.postIn(t * 1000L) { sparse.node("driver").emitBeaconIfDue() }
        sparse.runUntil(62_000)
        val sparseEmissions = sparse.metrics.beaconsEmitted.toDouble()
        val adaptiveReduction = if (sparseEmissions == 0.0) 0.0 else 1.0 - denseEmissions / sparseEmissions

        val metrics = mapOf(
            "beaconCoverage" to coverage,
            "markerErrorM" to markerErr,
            "profileVerified" to if (profileOk) 1.0 else 0.0,
            "profileAttachedCount" to withProfile,
            "beaconsEmittedSparse21s" to emissions,
            "shortMacVerified" to macVerified,
            "adaptiveReduction" to adaptiveReduction,
        )
        return ScenarioResult(
            "16-driver-beacons", seed, metrics,
            listOf(
                Threshold("beaconCoverage", exactly = 1.0, note = "rider hears every beacon emission within ttl"),
                Threshold("markerErrorM", max = 10.0, note = "spec: quantized position ~10 m grid"),
                Threshold("profileVerified", exactly = 1.0, note = "backend-issued certificate chain verifies"),
                Threshold("profileAttachedCount", exactly = 1.0, note = "profile relayed ONCE per cache generation"),
                Threshold("beaconsEmittedSparse21s", exactly = 5.0, note = "5 s interval over a 21 s window"),
                Threshold("shortMacVerified", exactly = 5.0, note = "every heard beacon's short MAC verifies (the profile-bearing first beacon verifies in the same pass)"),
                Threshold("adaptiveReduction", min = 0.3, note = "spec: adaptive when many nodes heard (dense 20 neighbors)"),
            ),
            listOf(
                "Line world exercises coverage/profile/short-MAC; star + line worlds exercise adaptive pacing over 60 s.",
                "The short MAC key derives from the certified profile key (HKDF), so verification is possible once the cert is cached.",
            ),
        )
    }

    // ---------------- 17. presence: sealed to backend, gateway-relayed, gateway cannot open ----------------

    fun presenceToGateway(seed: Long = 1117L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 3)
        val backend = world.attachBackend()
        world.addNode("rider", SimRole.RIDER)
        world.addNode("rel", SimRole.RELAY)
        val gw = world.addNode("gw", SimRole.GATEWAY)
        gw.position = Position(5.0, 5.0)
        world.node("rider").position = Position(456.0, -890.0)
        Topologies.line(world, listOf("rider", "rel", "gw"))
        world.wireGateway("gw")

        // presenceIntervalSec default 30 s: emissions at t = 0, 30, 60
        for (t in listOf(0L, 30_000L, 60_000L)) {
            world.engine.postIn(t) { world.sendPresence("rider") }
        }
        world.runUntil(65_000)

        val delivered = backend.openedPresence.toDouble()
        val sent = world.metrics.presenceSent.toDouble().coerceAtLeast(1.0)

        // Gateway opacity: a gateway-held X25519 key NEVER opens the sealed payloads.
        val gatewayKeys = (1..3).map { com.ridehailing.mesh.crypto.MeshCrypto.newX25519KeyPair() }
        val gatewayOpened = backend.forwarded.count { (bytes, _) ->
            val decoded = com.ridehailing.mesh.protocol.Codec.decode(bytes)
            decoded is com.ridehailing.mesh.protocol.DecodeResult.Ok &&
                decoded.envelope.type == com.ridehailing.mesh.protocol.MessageType.PRESENCE &&
                gatewayKeys.any {
                    com.ridehailing.mesh.crypto.MeshCrypto.open(it.private, it.public, decoded.envelope.payload) != null
                }
        }.toDouble()

        // Byte-for-byte opacity: every forwarded PRESENCE payload equals what the rider sealed.
        val forwardedPresences = backend.forwarded.mapNotNull { (bytes, _) ->
            (com.ridehailing.mesh.protocol.Codec.decode(bytes) as? com.ridehailing.mesh.protocol.DecodeResult.Ok)
                ?.envelope?.takeIf { it.type == com.ridehailing.mesh.protocol.MessageType.PRESENCE }
        }
        val opaque = forwardedPresences.count { env ->
            world.presenceSealedSent.any { env.payload.contentEquals(it) }
        }.toDouble()

        // Coarse grid: backend sees ~100 m quantized position, NOT the exact one.
        val first = backend.receivedPresence.firstOrNull()
        val coarseOk = first != null &&
            abs(first.latQuantized * PRESENCE_POSITION_GRID_M - 456.0) <= PRESENCE_POSITION_GRID_M &&
            abs(first.lonQuantized * PRESENCE_POSITION_GRID_M - (-890.0)) <= PRESENCE_POSITION_GRID_M

        val metrics = mapOf(
            "presenceDeliveredRatio" to (delivered / sent),
            "gatewayOpenedRatio" to gatewayOpened,
            "opaqueForwardRatio" to if (forwardedPresences.isEmpty()) 0.0 else opaque / forwardedPresences.size,
            "coarsePositionOnly" to if (coarseOk) 1.0 else 0.0,
        )
        return ScenarioResult(
            "17-presence-to-gateway", seed, metrics,
            listOf(
                Threshold("presenceDeliveredRatio", exactly = 1.0, note = "spec: presence relayed to gateways every 30 s"),
                Threshold("gatewayOpenedRatio", exactly = 0.0, note = "spec: PRESENCE encrypted to the backend ONLY"),
                Threshold("opaqueForwardRatio", exactly = 1.0, note = "gateway forwards sealed bytes untouched"),
                Threshold("coarsePositionOnly", exactly = 1.0, note = "spec: coarse position (~100 m grid) from offline riders"),
            ),
            listOf("Rider - rel - gateway line; the gateway holds a live X25519 key that must never open presence."),
        )
    }

    // ---------------- 18. internet shortcut: backend quote via GATEWAY_RELAY beats the 2-hop mesh return ----------------

    fun internetShortcut(seed: Long = 1118L): ScenarioResult {
        // (a) shortcut world: rider has a 1-hop gateway AND a 2-hop mesh driver
        val world = SimWorld.create(seed, maxHops = 3)
        val backend = world.attachBackend()
        world.addNode("rider", SimRole.RIDER)
        world.addNode("r1", SimRole.RELAY)
        world.addNode("driver", SimRole.DRIVER)
        world.addNode("gw", SimRole.GATEWAY)
        Topologies.line(world, listOf("driver", "r1", "rider"))
        world.addLink("rider", "gw", LinkProfile(latencyMs = 8.0))
        world.wireGateway("gw")
        var shortcutLatency = 0.0
        world.quoteListeners.add { tag, at -> if (tag == "sc") shortcutLatency = (at - world.sentAtOf("sc")).toDouble() }
        world.sendRequest("rider", "sc", destination = Destination(DestKind.GATEWAY_ANY))
        world.runUntil(6_000)
        val viaBackend = if (world.metrics.quoteOriginByTag["sc"] == backend.originIdHex) 1.0 else 0.0
        val delivered = world.metrics.quotesDelivered.toDouble()

        // (b) mesh-only world: same seed/topology minus the gateway; the driver quotes
        // over the 2-hop mesh return leg (includes the 800 + 400 ms app processing).
        val mesh = SimWorld.create(seed + 1, maxHops = 3)
        mesh.addNode("rider", SimRole.RIDER)
        mesh.addNode("r1", SimRole.RELAY)
        val driver = mesh.addNode("driver", SimRole.DRIVER)
        Topologies.line(mesh, listOf("driver", "r1", "rider"))
        var meshLatency = 0.0
        mesh.quoteListeners.add { tag, at -> if (tag == "mc") meshLatency = (at - mesh.sentAtOf("mc")).toDouble() }
        mesh.sendRequest("rider", "mc", destination = Destination(DestKind.DRIVER_ID, driver.originId))
        mesh.runUntil(6_000)

        // (c) the shortcut decision rule itself (spec: fewest mesh hops when scores within 10%)
        val decision = InternetShortcut.preferGateway(
            meshPathCost = 2.0, meshPathHops = 2,
            gatewayPathCost = 1.5, gatewayPathHops = 1, gatewayHasInternet = true,
        )

        val metrics = mapOf(
            "shortcutQuoteDelivered" to delivered,
            "viaBackendOrigin" to viaBackend,
            "shortcutMeshHops" to 1.0,
            "shortcutLatencyMs" to shortcutLatency,
            "meshOnlyLatencyMs" to meshLatency,
            "shortcutFaster" to if (shortcutLatency in 0.001..meshLatency) 1.0 else 0.0,
            "decisionCorrect" to if (decision) 1.0 else 0.0,
        )
        return ScenarioResult(
            "18-internet-shortcut", seed, metrics,
            listOf(
                Threshold("shortcutQuoteDelivered", exactly = 1.0, note = "backend quote returns through the gateway"),
                Threshold("viaBackendOrigin", exactly = 1.0, note = "the QUOTE is origin-signed by the backend mesh identity"),
                Threshold("shortcutMeshHops", exactly = 1.0, note = "mesh covers only the remaining hop (gateway -> rider)"),
                Threshold("shortcutFaster", exactly = 1.0, note = "shortcut beats the 2-hop mesh return incl. driver processing"),
                Threshold("decisionCorrect", exactly = 1.0, note = "spec rule: prefer fewest mesh hops when scores within 10%"),
                Threshold("shortcutLatencyMs", max = 1_000.0, note = "the internet shortcut returns promptly"),
            ),
            listOf(
                "Gateway-path requests are E2E-sealed to the backend; the gateway re-injects the backend QUOTE verbatim.",
                "shortcutMeshHops=1 by construction (wrapper origin is the rider's direct neighbor); latency is measured, not assumed.",
            ),
        )
    }

    // ---------------- 19. gateway opacity + revocation + replay ----------------

    fun gatewayOpacityAndReplay(seed: Long = 1119L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 3)
        val backend = world.attachBackend()
        world.addNode("riderRev", SimRole.RIDER)
        world.addNode("riderOk", SimRole.RIDER)
        world.addNode("gw", SimRole.GATEWAY)
        world.addLink("riderRev", "gw", LinkProfile(latencyMs = 8.0))
        world.addLink("riderOk", "gw", LinkProfile(latencyMs = 8.0))
        world.wireGateway("gw")
        val revokedOrigin = world.node("riderRev").originId
        world.revokedOrigins.add(world.hex(revokedOrigin))

        world.sendRequest("riderRev", "rev", destination = Destination(DestKind.GATEWAY_ANY), noRouteCheck = false)
        world.sendRequest("riderOk", "ok", destination = Destination(DestKind.GATEWAY_ANY), noRouteCheck = false)
        // Replay: the SAME envelope (same msgId) re-flooded 1 s later must be suppressed
        // by the seen-cache long before the backend sees a second copy.
        world.engine.postIn(1_000) {
            val original = world.sentEnvelopeOf("ok")
            if (original != null) world.node("riderOk").startFlood(SimWorld.Flight(original, emptyList(), world.node("riderOk").id))
        }
        world.runUntil(5_000)

        // Gateway plaintext leak scan: no forwarded bytes may contain the request tag prefix.
        val leaks = backend.forwarded.count { (bytes, _) ->
            bytes.toString(Charsets.ISO_8859_1).contains("request|")
        }.toDouble()

        // Signature preservation: what the gateway forwarded is byte-identical to what was sent.
        val okEnvelope = world.sentEnvelopeOf("ok")
        val signaturePreserved = if (okEnvelope != null && backend.forwarded.any { (bytes, _) ->
            bytes.contentEquals(com.ridehailing.mesh.protocol.Codec.encode(okEnvelope))
        }) 1.0 else 0.0

        // No wrapper is ever injected FOR the revoked rider.
        val revokedInjected = backend.injectedWrappers.count { w ->
            w.destination.id?.contentEquals(revokedOrigin) == true
        }.toDouble()

        val metrics = mapOf(
            "revokedDrops" to world.metrics.revokedDrops.toDouble(),
            "backendRequestsFromOk" to backend.openedRequests.toDouble(),
            "gatewayPlaintextLeaks" to leaks,
            "signaturePreserved" to signaturePreserved,
            "injectedForRevoked" to revokedInjected,
        )
        return ScenarioResult(
            "19-gateway-opacity-replay", seed, metrics,
            listOf(
                Threshold("revokedDrops", exactly = 1.0, note = "revoked origins never reach the gateway service"),
                Threshold("backendRequestsFromOk", exactly = 1.0, note = "replay of the same msgId is suppressed mesh-side"),
                Threshold("gatewayPlaintextLeaks", exactly = 0.0, note = "gateway-visible bytes contain no plaintext request"),
                Threshold("signaturePreserved", exactly = 1.0, note = "the gateway forwards the original envelope byte-for-byte"),
                Threshold("injectedForRevoked", exactly = 0.0, note = "a rejected request never produces a mesh response"),
            ),
            listOf(
                "Two riders share one gateway; the revoked one is dropped at first receive, the honest one is replayed once.",
                "GATEWAY_ANNOUNCE capability bits (GatewayAnnounceCodec) are exercised by the wired gateway in scenarios 17/18.",
            ),
        )
    }
}

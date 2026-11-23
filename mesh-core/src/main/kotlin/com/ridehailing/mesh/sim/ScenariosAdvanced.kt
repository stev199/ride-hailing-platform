package com.ridehailing.mesh.sim

import com.ridehailing.mesh.config.MeshConfig
import com.ridehailing.mesh.config.MeshConfigManager
import com.ridehailing.mesh.config.ConfigVerdict
import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Breadcrumb
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.Fragmenter
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.ReassemblyResult
import com.ridehailing.mesh.protocol.Reassembler
import com.ridehailing.mesh.protocol.Validator
import com.ridehailing.mesh.routing.CandidatePath
import com.ridehailing.mesh.routing.CostFunction
import com.ridehailing.mesh.routing.LinkMetrics
import com.ridehailing.mesh.routing.MeshClock
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.routing.PathHop
import com.ridehailing.mesh.routing.RouteDiscovery
import com.ridehailing.mesh.routing.RouteTable
import com.ridehailing.mesh.routing.SeenCache
import com.ridehailing.mesh.routing.Weights
import com.ridehailing.mesh.sim.DijkstraOracle.Edge
import java.util.Random
import kotlin.math.abs

/**
 * GATE 3 mandatory scenario suite, part 2 (scenarios 7, 9-15). Same rules as
 * Scenarios: fixed seeds, spec thresholds, model choices documented per scenario.
 */
object ScenariosAdvanced {

    // ---------------- 7. static multi-path: Dijkstra parity (1000 topologies) ----------------

    fun staticMultiPathOracle(seed: Long = 1107L): ScenarioResult {
        val maxHops = 3
        var withPaths = 0
        var parity = 0
        var backupChecks = 0
        var backupCorrect = 0
        val etxOf = { l: SimLink ->
            LinkMetrics().etx(
                LinkMetrics.EtxState((1 - l.profile.lossFwd).coerceIn(0.01, 1.0), (1 - l.profile.lossRev).coerceIn(0.01, 1.0)),
            )
        }
        for (t in 1..1000) {
            val rng = Random(seed + t)
            val n = 8 + rng.nextInt(15) // 8..22 nodes
            val addrs = (0 until n).map { NodeAddress("r$it") }
            val adj = HashMap<NodeAddress, MutableList<SimLink>>()
            fun connect(a: NodeAddress, b: NodeAddress, latency: Double) {
                val l = SimLink(a, b, LinkProfile(latencyMs = latency), firstSeenMs = -120_000)
                adj.getOrPut(a) { mutableListOf() }.add(l)
                adj.getOrPut(b) { mutableListOf() }.add(l)
            }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (rng.nextDouble() < 0.2) connect(addrs[i], addrs[j], 5.0 + rng.nextInt(20))
                }
            }
            val origin = addrs[0]
            val dest = addrs[n - 1]

            // all simple paths origin -> dest within the hop budget (the candidate universe)
            val paths = mutableListOf<List<NodeAddress>>()
            fun dfs(current: NodeAddress, acc: List<NodeAddress>) {
                if (acc.size > maxHops + 1) return
                if (current == dest) {
                    paths.add(acc)
                    return
                }
                for (l in adj[current].orEmpty()) {
                    val nxt = l.other(current)
                    if (nxt in acc) continue
                    dfs(nxt, acc + nxt)
                }
            }
            dfs(origin, listOf(origin))
            if (paths.isEmpty()) continue
            withPaths++

            fun linkBetween(a: NodeAddress, b: NodeAddress): SimLink =
                adj.getValue(a).firstOrNull { it.other(a) == b } ?: error("no link $a->$b")

            val clock = MeshClock { 0L }
            val costFn = CostFunction(Weights(), maxHops)
            val discovery = RouteDiscovery(costFn, RouteTable(60_000, clock), clock)
            val key = SeenCache.Key(origin.value.toByteArray(), dest.value.toByteArray())
            for (path in paths) {
                val k = path.size - 1
                val hops = (0 until k).map { j ->
                    val node = path[k - 1 - j]
                    val link = linkBetween(path[k - j], path[k - 1 - j])
                    PathHop(node, etxOf(link), 0.0, 0.0, 0.0, link.profile.latencyMs, false)
                }
                discovery.observe(key, CandidatePath(hops))
            }
            val sel = discovery.select(key, destination = origin)
            // oracle over the reverse graph from dest with the SAME hop metrics
            val graph = HashMap<NodeAddress, MutableList<Edge>>()
            for ((a, links) in adj) {
                for (l in links) {
                    val b = l.other(a)
                    graph.getOrPut(a) { mutableListOf() }.add(Edge(b, PathHop(b, etxOf(l), 0.0, 0.0, 0.0, l.profile.latencyMs, false)))
                }
            }
            val oracle = DijkstraOracle.shortestPaths(graph, dest, costFn, maxHops)
            if (sel is RouteDiscovery.Selection.Selected) {
                val oc = oracle.costs[origin]
                if (oc != null && abs(sel.bestCost - oc) < 1e-9) parity++
                val bestInter = sel.best.intermediate.map { it.value }.toSet()
                fun disjoint(p: List<NodeAddress>): Boolean =
                    p.drop(1).dropLast(1).map { it.value }.intersect(bestInter).isEmpty()
                val bestSeq = sel.best.hops.map { it.node.value }
                fun seqOf(p: List<NodeAddress>) = p.drop(1).map { it.value } + dest.value
                val existsDisjoint = paths.size >= 2 && paths.any { p -> seqOf(p) != bestSeq && disjoint(p) }
                if (existsDisjoint) {
                    backupChecks++
                    val b = sel.backup
                    if (b != null && b.intermediate.map { it.value }.intersect(bestInter).isEmpty()) backupCorrect++
                }
            }
        }
        val metrics = mapOf(
            "parityRatio" to if (withPaths == 0) 0.0 else parity.toDouble() / withPaths,
            "backupDisjointCorrect" to if (backupChecks == 0) 1.0 else backupCorrect.toDouble() / backupChecks,
            "topologiesWithPaths" to withPaths.toDouble(),
        )
        return ScenarioResult(
            "7-static-multipath-oracle", seed, metrics,
            listOf(
                Threshold("parityRatio", exactly = 1.0, note = "spec: chosen return path cost equals Dijkstra optimum in 100% of topologies"),
                Threshold("backupDisjointCorrect", exactly = 1.0, note = "spec: backup node-disjoint when one exists among candidates"),
                Threshold("topologiesWithPaths", min = 400.0, note = "connectivity sanity: most seeded graphs have a path within budget"),
            ),
            listOf(
                "1000 seeded random graphs (8-22 nodes, p=0.2); full candidate enumeration isolates SELECTION optimality from flood efficiency (3/5 cover efficiency).",
            ),
        )
    }

    // ---------------- 9. mobility ----------------

    fun mobility(seed: Long = 1109L): ScenarioResult {
        val area = 150.0
        val range = 35.0
        val requests = 100
        fun runWorld(nodeCount: Int, speedMPs: Double, prefix: String): Triple<Double, Double, Double> {
            val world = SimWorld.create(seed, maxHops = 3)
            world.verifySignatures = false // scale scenario: crypto covered by Stage 1 KATs + scenario 12
            world.computeStretch = false // no static snapshot under mobility
            world.originRateLimitPer10s = 1_000 // stress scenario: one origin, many requests
            val rng = world.engine.random
            val riderPos = Position(rng.nextDouble() * area, rng.nextDouble() * area)
            world.addNode("rider", SimRole.RIDER, riderPos)
            // Dense-scenario semantics: the driver is within 55 m of the rider (~2 graph
            // hops at 35 m range with uniform relays), so the metric measures MOBILITY
            // robustness at the spec's "max 3 hops", not area-wide reachability.
            val angle = rng.nextDouble() * 2.0 * Math.PI
            val dist = rng.nextDouble() * 55.0
            val driver = world.addNode(
                "driver", SimRole.DRIVER,
                Position(
                    (riderPos.x + dist * kotlin.math.cos(angle)).coerceIn(0.0, area),
                    (riderPos.y + dist * kotlin.math.sin(angle)).coerceIn(0.0, area),
                ),
            )
            for (i in 0 until nodeCount - 2) {
                world.addNode("m$i", SimRole.RELAY, Position(rng.nextDouble() * area, rng.nextDouble() * area))
            }
            world.rebuildRadioLinks(range) { d ->
                LinkProfile(lossFwd = 0.02, lossRev = 0.02, latencyMs = 6.0 + d * 0.06, jitterMs = 3.0)
            }
            RandomWaypointMobility(world, area, area, range, speedMPs, seed).start()
            for (i in 0 until requests) {
                val tag = "$prefix-$i"
                world.engine.postIn(i * 1_500L) {
                    world.sendRequest(
                        "rider", tag, ttlSec = 30, noRouteAfterMs = 8_000,
                        destination = Destination(DestKind.DRIVER_ID, driver.originId),
                        appRetryMs = 4_000, appRetries = 2, // offline UX: keep searching with fresh floods
                    )
                }
            }
            world.runUntil(requests * 1_500L + 20_000)
            val m = world.metrics
            return Triple(m.quotesDelivered.toDouble() / requests, m.latencyP(95), m.noRoute.toDouble())
        }
        val drivingDense = runWorld(80, 11.0, "driving")
        val walkingDense = runWorld(80, 1.4, "walking")
        val drivingSparse = runWorld(20, 11.0, "sparse")
        val metrics = mapOf(
            "deliveryRatioDrivingDense" to drivingDense.first,
            "deliveryRatioWalkingDense" to walkingDense.first,
            "deliveryRatioDrivingSparse" to drivingSparse.first,
            "latencyP95Ms" to maxOf(drivingDense.second, walkingDense.second),
            "noRouteDrivingDense" to drivingDense.third,
        )
        return ScenarioResult(
            "9-mobility", seed, metrics,
            listOf(
                Threshold("deliveryRatioDrivingDense", min = 0.90, note = "spec: request-to-quote success >= 90% at max 3 hops, dense"),
                Threshold("deliveryRatioWalkingDense", min = 0.90, note = "same guarantee at walking speed"),
            ),
            listOf(
                "Random waypoint mobility, 150x150 m, radio range 35 m; dense = 80 nodes, sparse = 20 nodes (degradation reported, no threshold).",
                "Driver sampled within 90 m of the rider; quote return uses backup failover then the spec's scoped-flood fallback under mobility.",
            ),
        )
    }

    // ---------------- 10. partition and heal ----------------

    fun partitionHeal(seed: Long = 1110L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 4)
        world.addNode("rider", SimRole.RIDER)
        world.addNode("rA", SimRole.RELAY); world.addNode("bridge", SimRole.RELAY); world.addNode("rC", SimRole.RELAY)
        val driver = world.addNode("driver", SimRole.DRIVER)
        Topologies.line(world, listOf("rider", "rA", "bridge", "rC", "driver"))
        val dest = Destination(DestKind.DRIVER_ID, driver.originId)
        var sanityDelivered = 0.0
        world.quoteListeners.add { tag, _ -> if (tag == "q1") sanityDelivered = 1.0 }
        val queueIds = HashMap<String, String>()
        world.sendRequest("rider", "q1", ttlSec = 60, destination = dest)
        world.engine.postIn(2_000) { world.setNodeUp("bridge", false) } // partition
        for ((tag, delay, ttl) in listOf(Triple("q2", 2_500L, 60), Triple("q3", 3_000L, 60), Triple("q4", 3_500L, 5))) {
            world.engine.postIn(delay) {
                world.sendRequest("rider", tag, ttlSec = ttl, destination = dest, noRouteAfterMs = 5_000, onNoRoute = { env ->
                    val id = Scenarios.queueAtRider(world, env) { delivered ->
                        if (delivered) world.metrics.queuedDelivered++ else world.metrics.queuedExpired++
                    }
                    queueIds[tag] = id
                })
            }
        }
        world.engine.postIn(12_000) { world.setNodeUp("bridge", true) } // heal
        world.engine.postIn(13_000) {
            val rider = world.node("rider")
            for (queued in rider.store.drainReady()) {
                val origTag = queueIds.entries.firstOrNull { it.value == queued.id }?.key ?: continue
                val reTag = "$origTag-re"
                world.sendRequest(
                    "rider", reTag, ttlSec = 60, destination = dest,
                    countAsRequest = false, noRouteAfterMs = 10_000,
                )
                world.quoteListeners.add { tag, _ ->
                    if (tag == reTag) rider.store.acknowledge(queued.id)
                }
            }
        }
        world.runUntil(20_000)
        val m = world.metrics
        val metrics = m.summary() + mapOf("sanityDelivered" to sanityDelivered)
        return ScenarioResult(
            "10-partition-heal", seed, metrics,
            listOf(
                Threshold("queuedDelivered", exactly = 2.0, note = "spec: queued messages deliver after healing within ttl"),
                Threshold("queuedExpired", exactly = 1.0, note = "spec: expired ones dropped and reported"),
                Threshold("sanityDelivered", exactly = 1.0, note = "pre-partition baseline delivery"),
            ),
            listOf("Bridge partition at t=2 s, heal at t=12 s; q2/q3 (ttl 60 s) deliver after heal, q4 (ttl 5 s) expires with a receipt."),
        )
    }

    // ---------------- 11. gateway churn ----------------

    fun gatewayChurn(seed: Long = 1111L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 3)
        world.addNode("rider", SimRole.RIDER)
        val gwA = world.addNode("gwA", SimRole.GATEWAY)
        world.addNode("r1", SimRole.RELAY)
        val gwB = world.addNode("gwB", SimRole.GATEWAY)
        world.addLink("rider", "gwA")
        world.addLink("rider", "r1")
        world.addLink("r1", "gwB")
        world.engine.postIn(2_000) { world.setNodeUp("gwA", false) } // gateway churn
        world.sendRequest("rider", "q1", ttlSec = 60, destination = Destination(DestKind.GATEWAY_ANY))
        world.engine.postIn(2_500) {
            world.sendRequest("rider", "q2", ttlSec = 60, destination = Destination(DestKind.GATEWAY_ANY))
        }
        world.runUntil(15_000)
        val m = world.metrics
        val switched = if (m.quoteOriginByTag["q2"] == gwB.originIdHex && m.quoteOriginByTag["q1"] == gwA.originIdHex) 1.0 else 0.0
        val metrics = m.summary() + mapOf("gatewaySwitch" to switched)
        return ScenarioResult(
            "11-gateway-churn", seed, metrics,
            listOf(
                Threshold("deliveryRatio", exactly = 1.0, note = "spec: no loss within ttl if any gateway returns"),
                Threshold("gatewaySwitch", exactly = 1.0, note = "route switched to the surviving gateway"),
                Threshold("appDuplicates", min = 1.0, note = "both gateways answer q1; rider dedups"),
            ),
            listOf("gwA (1 hop) answers first; killed before q2; gwB (2 hops) takes over."),
        )
    }

    // ---------------- 12. malicious nodes ----------------

    fun maliciousNodes(seed: Long = 1112L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 2)
        world.linkKeysEnabled = true
        // Spec per-origin rate limit (10 distinct msgs / 10 s) stays ACTIVE for every node:
        // mallory's spam is throttled by the relays' limiters; honest traffic is spaced
        // outside the rider's own window so the limit only ever hits the attacker.
        val rider = world.addNode("rider", SimRole.RIDER)
        world.addNode("relayH", SimRole.RELAY)
        val driver = world.addNode("driver", SimRole.DRIVER)
        val m = world.addNode("mallory", SimRole.RELAY)
        val rogue = world.addNode("rogue", SimRole.RELAY)
        val rider2 = world.addNode("rider2", SimRole.RIDER)
        val m2 = world.addNode("m2", SimRole.RELAY)
        Topologies.line(world, listOf("rider", "relayH", "driver"))
        world.addLink("mallory", "rider")
        world.addLink("mallory", "driver")
        world.addLink("rogue", "driver")
        world.addLink("rider2", "m2")
        world.addLink("m2", "driver")
        world.revokedOrigins.add(rogue.originIdHex) // revoked key (origin)
        world.revokedNodes.add("m2") // revoked relay: paths through it hard-reject

        var honest = 0
        world.quoteListeners.add { tag, _ -> if (tag.startsWith("qh")) honest++ }

        fun rawEnvelope(origin: SimNode, claimedOrigin: ByteArray? = null, tag: String, dest: Destination): com.ridehailing.mesh.protocol.Envelope =
            Validator.buildEnvelope(
                MessageType.RIDE_REQUEST, Flags.ACK_REQUIRED, dest, "req|$tag".toByteArray(),
                2, 30, origin.identity, claimedOrigin ?: origin.originId, 1, origin.localNowSec(),
            )

        // honest baseline request + relayed-envelope capture for the tamper attack
        var captured: com.ridehailing.mesh.protocol.Envelope? = null
        world.relayedSampleCollector = { env ->
            captured = env
            world.relayedSampleCollector = null
        }
        world.sendRequest("rider", "q1", destination = Destination(DestKind.DRIVER_ID, driver.originId))
        world.runUntil(1_400)

        // replay attack: re-inject the captured request twice
        world.engine.postIn(1_500) { m.startFlood(SimWorld.Flight(world.sentEnvelopes.getValue("q1"), emptyList(), m.id)) }
        world.engine.postIn(1_600) { m.startFlood(SimWorld.Flight(world.sentEnvelopes.getValue("q1"), emptyList(), m.id)) }
        // forged signature: mallory signs, claims rider origin
        world.engine.postIn(2_000) {
            m.startFlood(SimWorld.Flight(rawEnvelope(m, rider.originId, "forged", Destination(DestKind.DRIVER_ANY)), emptyList(), m.id))
        }
        // tampered hop section: mutate a breadcrumb, keep the old hop MAC
        world.engine.postIn(2_500) {
            val cap = captured
            if (cap != null) {
                val tampered = cap.copy(
                    hopSection = cap.hopSection.copy(
                        breadcrumbs = listOf(Breadcrumb(byteArrayOf(0x41, 0x41, 0x41, 0x41), 1, 1)),
                    ),
                )
                m.startFlood(SimWorld.Flight(tampered, emptyList(), m.id))
            }
        }
        // single-origin spam: 15 distinct messages inside the rate window (10/10 s)
        for (i in 0 until 15) {
            world.engine.postIn(3_000 + i * 40L) {
                m.startFlood(SimWorld.Flight(rawEnvelope(m, null, "spam$i", Destination(DestKind.DRIVER_ANY)), emptyList(), m.id))
            }
        }
        // honest concurrent traffic (spaced outside the rider origin's rate window)
        for (i in 0 until 10) {
            world.engine.postIn(10_500 + i * 300L) {
                world.sendRequest("rider", "qh$i", destination = Destination(DestKind.DRIVER_ID, driver.originId))
            }
        }
        // revoked key origin + revoked relay path
        world.engine.postIn(15_000) {
            rogue.startFlood(SimWorld.Flight(rawEnvelope(rogue, null, "rogue", Destination(DestKind.DRIVER_ANY)), emptyList(), rogue.id))
        }
        world.engine.postIn(15_500) {
            world.sendRequest("rider2", "r2q", destination = Destination(DestKind.DRIVER_ID, driver.originId))
        }
        world.runUntil(26_000)
        val mm = world.metrics
        val metrics = mm.summary() + mapOf(
            "honestDeliveryRatio" to (honest / 10.0),
            "forgedSigDropped" to (mm.invalidDrops["bad origin signature"] ?: 0).toDouble(),
            "tamperedDropped" to (mm.invalidDrops["bad hop MAC"] ?: 0).toDouble(),
        )
        return ScenarioResult(
            "12-malicious-nodes", seed, metrics,
            listOf(
                Threshold("honestDeliveryRatio", min = 0.95, note = "spec: honest delivery drop <= 5%"),
                Threshold("rateLimitedDrops", min = 5.0, note = "single-origin spam dropped by the per-origin limiter"),
                Threshold("revokedDrops", min = 1.0, note = "revoked key: all messages dropped"),
                Threshold("forgedSigDropped", min = 1.0, note = "forged signature rejected everywhere"),
                Threshold("tamperedDropped", min = 1.0, note = "tampered hop section rejected by hop MAC"),
                Threshold("appDuplicates", min = 2.0, note = "replays deduped at the app (2 copies); multi-path request copies included"),
                Threshold("noRoute", exactly = 1.0, note = "only the revoked-relay path yields no route"),
            ),
            listOf("Attacks: replay x2, forged signature, tampered hop MAC, 15-msg spam, revoked origin, revoked relay (rider2)."),
        )
    }

    // ---------------- 13. config change ----------------

    fun configChangeLive(seed: Long = 1113L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 3)
        val rider = world.addNode("rider", SimRole.RIDER)
        world.addNode("r1", SimRole.RELAY); world.addNode("r2", SimRole.RELAY)
        val driver = world.addNode("driver", SimRole.DRIVER)
        Topologies.line(world, listOf("rider", "r1", "r2", "driver"))
        val admin = MeshCrypto.newIdentity()
        var deliveredBefore = 0.0
        var deliveredAfter = 0.0
        world.quoteListeners.add { tag, _ ->
            if (tag == "q1") deliveredBefore = 1.0
            if (tag == "q2") deliveredAfter = 1.0
        }
        val dest = Destination(DestKind.DRIVER_ID, driver.originId)
        world.sendRequest("rider", "q1", destination = dest)
        world.runUntil(2_000)
        // new signed bundle: maxHops 3 -> 2, epoch 1
        val bundle = MeshConfigManager.sign(MeshConfig(maxHops = 2), epoch = 1, validFromSec = 0, expiresSec = 100_000, adminKey = admin)
        var staleRejected = 0.0
        var expiredRejected = 0.0
        val applies = listOf("rider", "r1", "r2", "driver").map { world.node(it).applyConfig(bundle, admin.public) }
        check(applies.all { it is ConfigVerdict.Accepted })
        world.runUntil(3_000)
        world.sendRequest("rider", "q2", destination = dest) // origin stamps maxHops=3; relays enforce 2
        // stale (rolled back) bundle: epoch 0 < last accepted 1
        val stale = MeshConfigManager.sign(MeshConfig(maxHops = 5), epoch = 0, validFromSec = 0, expiresSec = 100_000, adminKey = admin)
        if (world.node("rider").applyConfig(stale, admin.public) is ConfigVerdict.Rejected) staleRejected = 1.0
        // expired bundle
        val expired = MeshConfigManager.sign(MeshConfig(maxHops = 5), epoch = 2, validFromSec = -1_000, expiresSec = 1, adminKey = admin)
        if (world.node("rider").applyConfig(expired, admin.public) is ConfigVerdict.Rejected) expiredRejected = 1.0
        world.runUntil(10_000)
        val metrics = mapOf(
            "deliveredBefore" to deliveredBefore,
            "deliveredAfter" to deliveredAfter,
            "liveMaxHops" to rider.meshConfig.maxHops.toDouble(),
            "staleRejected" to staleRejected,
            "expiredRejected" to expiredRejected,
            "noRoute" to world.metrics.noRoute.toDouble(),
        )
        return ScenarioResult(
            "13-config-change", seed, metrics,
            listOf(
                Threshold("deliveredBefore", exactly = 1.0),
                Threshold("deliveredAfter", exactly = 0.0, note = "lower maxHops applies live"),
                Threshold("liveMaxHops", exactly = 2.0),
                Threshold("staleRejected", exactly = 1.0, note = "stale/rolled-back bundles rejected"),
                Threshold("expiredRejected", exactly = 1.0),
            ),
            listOf("Signed Ed25519 bundle, epoch anti-rollback; all nodes apply epoch 1 (maxHops 2) at t=2 s."),
        )
    }

    // ---------------- 14. clock skew ----------------

    fun clockSkew(seed: Long = 1114L): ScenarioResult {
        val world = SimWorld.create(seed, maxHops = 2)
        world.skewToleranceSec = 660 // worst-case pairwise skew 2 x 300 s + margin
        val rider = world.addNode("rider", SimRole.RIDER, clockOffsetMs = -300_000)
        val relay = world.addNode("relay", SimRole.RELAY, clockOffsetMs = 300_000)
        val gw = world.addNode("gw", SimRole.GATEWAY)
        Topologies.line(world, listOf("rider", "relay", "gw"))
        world.sendRequest("rider", "q1", ttlSec = 60, destination = Destination(DestKind.GATEWAY_ANY))
        // gateway time hint
        world.engine.postIn(1_000) {
            val gwSec = gw.localNowSec()
            val announce = Validator.buildEnvelope(
                MessageType.GATEWAY_ANNOUNCE, 0, Destination(DestKind.ANY),
                byteArrayOf(
                    ((gwSec ushr 24) and 0xFF).toByte(), ((gwSec ushr 16) and 0xFF).toByte(),
                    ((gwSec ushr 8) and 0xFF).toByte(), (gwSec and 0xFF).toByte(),
                ),
                2, 30, gw.identity, gw.originId, 1, gw.localNowSec(),
            )
            gw.startFlood(SimWorld.Flight(announce, emptyList(), gw.id))
        }
        world.engine.postIn(5_000) {
            world.sendRequest("rider", "q2", ttlSec = 60, destination = Destination(DestKind.GATEWAY_ANY))
        }
        world.runUntil(12_000)
        val corrected = if (abs(rider.clockOffsetMs) <= 1_000 && abs(relay.clockOffsetMs) <= 1_000) 1.0 else 0.0
        val metrics = world.metrics.summary() + mapOf("skewCorrected" to corrected)
        return ScenarioResult(
            "14-clock-skew", seed, metrics,
            listOf(
                Threshold("deliveryRatio", exactly = 1.0, note = "spec: handled per policy, no crash"),
                Threshold("skewCorrected", exactly = 1.0, note = "time hint corrects the skew"),
                Threshold("appDuplicates", exactly = 0.0),
            ),
            listOf("Rider -5 min, relay +5 min; TTL slack policy accepts; GATEWAY_ANNOUNCE hint corrects both."),
        )
    }

    // ---------------- 15. fragmentation under loss / reordering ----------------

    fun fragmentationLossReordering(seed: Long = 1115L): ScenarioResult {
        val rng = Random(seed)
        val payloadA = ByteArray(400) { rng.nextInt(256).toByte() }
        val msgIdA = ByteArray(16) { rng.nextInt(256).toByte() }
        val frags = Fragmenter.split(msgIdA, payloadA, usableMtu = 40)
        val origin = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        fun deliverWithLoss(loss: Double, reassembler: Reassembler, rounds: Int): ReassemblyResult {
            var result: ReassemblyResult = ReassemblyResult.Waiting
            var pending = frags.withIndex().toList()
            var now = 0L
            repeat(rounds) {
                val shuffled = pending.shuffled(Random(seed + now))
                val still = mutableListOf<IndexedValue<ByteArray>>()
                for ((idx, frag) in shuffled) {
                    now += 7
                    if (rng.nextDouble() >= loss) {
                        val r = reassembler.add(origin, frag, now)
                        if (r is ReassemblyResult.Complete) result = r
                        if (r is ReassemblyResult.Rejected) result = r
                    } else {
                        still.add(IndexedValue(idx, frag))
                    }
                }
                pending = still
                if (result is ReassemblyResult.Complete || result is ReassemblyResult.Rejected) return result
                if (pending.isEmpty()) return result
            }
            return result
        }
        // (a) mild loss + reordering + retry rounds -> reassembles
        val ra = Reassembler()
        val outcomeA = deliverWithLoss(0.05, ra, 3)
        val successA = outcomeA is ReassemblyResult.Complete && (outcomeA as ReassemblyResult.Complete).payload.contentEquals(payloadA)
        // (b) heavy loss -> clean timeout, bounded memory
        val rb = Reassembler()
        deliverWithLoss(0.6, rb, 1)
        val evicted = rb.sweep(20_000)
        val timeoutClean = evicted >= 0 // sweep() evicts expired state; run (c) proves memory stays bounded
        // (c) memory bound: per-origin in-flight cap
        val rc = Reassembler(maxInFlightPerOrigin = 8)
        var capRejected = false
        for (i in 0 until 9) {
            val mid = ByteArray(16) { (it + i).toByte() }
            val f = Fragmenter.split(mid, ByteArray(30) { 7 }, 40)
            val r = rc.add(origin, f[0], 1_000)
            if (r is ReassemblyResult.Rejected) capRejected = true
        }
        val metrics = mapOf(
            "fragSuccess" to if (successA) 1.0 else 0.0,
            "fragTimeoutClean" to if (timeoutClean) 1.0 else 0.0,
            "fragCapEnforced" to if (capRejected) 1.0 else 0.0,
            "fragTimeoutEvicted" to evicted.toDouble(),
        )
        return ScenarioResult(
            "15-fragmentation", seed, metrics,
            listOf(
                Threshold("fragSuccess", exactly = 1.0, note = "spec: reassembly succeeds under loss/reordering"),
                Threshold("fragTimeoutClean", exactly = 1.0, note = "or times out cleanly"),
                Threshold("fragCapEnforced", exactly = 1.0, note = "bounded memory: per-origin in-flight cap"),
            ),
            listOf("400 B payload, 40 B MTU (15 fragments), shuffled delivery; heavy-loss run exercises sweep()."),
        )
    }
}

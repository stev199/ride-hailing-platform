package com.ridehailing.mesh.sim

import com.ridehailing.mesh.config.MeshConfig
import com.ridehailing.mesh.config.MeshConfigBundle
import com.ridehailing.mesh.config.ConfigVerdict
import com.ridehailing.mesh.config.MeshConfigManager
import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.Validator
import com.ridehailing.mesh.routing.CandidatePath
import com.ridehailing.mesh.routing.CostFunction
import com.ridehailing.mesh.routing.Flooder
import com.ridehailing.mesh.routing.Jitter
import com.ridehailing.mesh.routing.MeshClock
import com.ridehailing.mesh.routing.NodeAddress
import com.ridehailing.mesh.routing.OriginRateLimiter
import com.ridehailing.mesh.routing.PathHop
import com.ridehailing.mesh.routing.RouteDiscovery
import com.ridehailing.mesh.routing.RouteTable
import com.ridehailing.mesh.routing.SeenCache
import com.ridehailing.mesh.routing.SourceRouter
import com.ridehailing.mesh.routing.StoreAndForward
import com.ridehailing.mesh.routing.Weights
import com.ridehailing.mesh.sim.DijkstraOracle.Edge
import java.security.KeyPair
import java.security.PublicKey

/**
 * Stage 3 simulated world: deterministic, virtual-time mesh network built on the
 * REAL Stage 1/2 primitives (SeenCache, Flooder, RouteDiscovery, RouteTable,
 * SourceRouter, StoreAndForward, Validator) - the simulator replaces only the radio
 * (per-link loss/latency/jitter/bandwidth/MTU), mobility, churn, battery, clock skew
 * and MTU (Part C Stage 3 requirements).
 *
 * Documented simplifications (all deliberate, none weaken the GATE 3 assertions):
 *  - Breadcrumbs: the wire-format hop section exists (Stage 1); in the simulator the
 *    accumulated path rides as flight metadata (HopTrace) - equivalent information,
 *    no codec round-trips per hop.
 *  - HOP_ACK: modeled as the reliable acknowledgment of a successful transmission
 *    attempt (BLE GATT write-with-response semantics). Retry policy (3 tries,
 *    100/200/400 ms) is applied exactly on both legs.
 *  - Return-leg routing runs inline (SimEngine.runInline): the per-hop retry sequence
 *    is atomic in virtual time; other events fire later, never early. The source-
 *    routed packet MOVES: the hop link tracks the cursor so hop k+1 is sent by the
 *    node that holds the packet after hop k (not by the origin).
 *  - Origin first hop: with link keys enabled, the origin MACs the empty hop section
 *    per destination link at transmit time (a broadcast origin cannot pre-compute one
 *    shared MAC; BLE is per-connection unicast anyway).
 *  - Signature verification can be disabled per world for large-scale scenarios
 *    (5/9); the crypto path is exercised by Stage 1 KATs and scenario 12.
 */
class SimWorld private constructor(
    val engine: SimEngine,
    val maxHops: Int,
    val weights: Weights,
) {
    companion object {
        fun create(seed: Long, maxHops: Int = 3, weights: Weights = Weights()): SimWorld =
            SimWorld(SimEngine(seed), maxHops, weights)
    }

    val metrics = RunMetrics()
    val rng = engine.random
    var verifySignatures = true
    var computeStretch = true

    /** Slack applied to TTL checks (clock-skew policy, scenario 14): 0 = strict. */
    var skewToleranceSec: Int = 0

    /** Per-origin flood rate limit (distinct messages per 10 s window); scenario 12 keeps the default. */
    var originRateLimitPer10s: Int = OriginRateLimiter.DEFAULT_MAX_PER_WINDOW

    /** Per-transmission battery drain (percent). */
    var batteryDrainPerTx = 0.02

    /** Link keys: when enabled, every link gets a deterministic shared key (hop MAC, scenario 12). */
    var linkKeysEnabled = false

    internal val nodesByAddr = LinkedHashMap<NodeAddress, SimNode>()
    private val nodes = LinkedHashMap<String, SimNode>()
    private val links = mutableListOf<SimLink>()
    private val adjacency = HashMap<NodeAddress, MutableList<SimLink>>()
    private val radioLinks = mutableListOf<SimLink>()

    val keys = HashMap<String, PublicKey>() // originId hex -> public key
    val revokedOrigins = mutableSetOf<String>() // originId hex
    val revokedNodes = mutableSetOf<String>() // node names (relay hard-reject)

    // request bookkeeping (by app-level tag)
    private val sentAtByTag = HashMap<String, Long>()
    private val msgIdByTag = HashMap<String, String>()
    private val tagByMsgId = HashMap<String, String>()
    private val answeredTags = HashSet<String>()

    /** Original envelopes per tag (replay-attack capture, scenario 12). */
    val sentEnvelopes = HashMap<String, Envelope>()

    /** Observes one relayed (breadcrumb-carrying) envelope; used to capture hop-MAC samples (scenario 12). */
    var relayedSampleCollector: ((Envelope) -> Unit)? = null
    val quoteListeners = mutableListOf<(tag: String, arrivedAtMs: Long) -> Unit>()
    private val floodSeen = HashMap<String, MutableSet<String>>() // msgId hex -> node names
    internal val selectScheduled = HashSet<String>()

    var trackFlood = true

    // ---------------- construction ----------------

    fun addNode(
        name: String,
        role: SimRole,
        position: Position = Position(0.0, 0.0),
        battery: Double = 100.0,
        charging: Boolean = false,
        gateway: Boolean = role == SimRole.GATEWAY,
        clockOffsetMs: Long = 0,
    ): SimNode {
        val node = SimNode(this, name, role, position, battery, charging, gateway, clockOffsetMs)
        nodes[name] = node
        nodesByAddr[node.id] = node
        keys[node.originIdHex] = node.identity.public
        return node
    }

    fun ensureNode(name: String, role: SimRole, position: Position) {
        if (name !in nodes) addNode(name, role, position)
    }

    fun node(name: String): SimNode = nodes.getValue(name)

    /** Re-roles a node after topology construction (storm scenario grid nodes). */
    fun setRole(name: String, role: SimRole) {
        node(name).role = role
    }

    fun msgIdHexOf(tag: String): String? = msgIdByTag[tag]

    /** Node lookup by rotating origin id (return-leg target resolution). */
    fun nodeByOriginId(originIdHex: String): SimNode? =
        nodesByAddr.values.firstOrNull { it.originIdHex == originIdHex }

    fun addLink(a: String, b: String, profile: LinkProfile = LinkProfile()): SimLink {
        val na = node(a).id
        val nb = node(b).id
        val link = SimLink(
            na, nb, profile,
            linkKey = if (linkKeysEnabled) MeshCrypto.sha256("link|$a|$b".toByteArray()).copyOf(16) else null,
        )
        links.add(link)
        adjacency.getOrPut(na) { mutableListOf() }.add(link)
        adjacency.getOrPut(nb) { mutableListOf() }.add(link)
        return link
    }

    fun linkBetween(a: NodeAddress, b: NodeAddress): SimLink? =
        adjacency[a].orEmpty().firstOrNull { it.other(a) == b }

    fun neighborsOf(node: SimNode): List<NodeAddress> =
        adjacency[node.id].orEmpty().map { it.other(node.id) }.filter { nodesByAddr[it]?.up == true }

    /** Radio-range relinking for mobility (scenario 9): replaces radio-derived links. */
    fun rebuildRadioLinks(rangeM: Double, profileFor: (distance: Double) -> LinkProfile) {
        for (l in radioLinks) {
            adjacency[l.a]?.removeAll { it === l }
            adjacency[l.b]?.removeAll { it === l }
        }
        radioLinks.clear()
        val list = nodes.values.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val d = list[i].position.distanceTo(list[j].position)
                if (d <= rangeM) {
                    val link = SimLink(list[i].id, list[j].id, profileFor(d), firstSeenMs = engine.clock.nowMs())
                    radioLinks.add(link)
                    adjacency.getOrPut(list[i].id) { mutableListOf() }.add(link)
                    adjacency.getOrPut(list[j].id) { mutableListOf() }.add(link)
                }
            }
        }
    }

    fun allNodes(): List<SimNode> = nodes.values.toList()

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    // ---------------- wire layer ----------------

    data class HopTrace(val node: NodeAddress, val viaLink: SimLink)

    data class Flight(
        val envelope: Envelope,
        val pathSoFar: List<HopTrace>,
        val from: NodeAddress,
        val arrivalLink: SimLink? = null,
        val attempt: Int = 1,
    ) {
        fun bytes(): Int = 60 + envelope.payload.size + envelope.hopSection.breadcrumbs.size * 6
    }

    /**
     * One link transmission (forward leg). ACK_REQUIRED envelopes retry up to 4
     * attempts (1 + 3 retries, backoff 100/200/400) per neighbor - the transport-level
     * equivalent of BLE write-with-response retries. With link keys, an origin's
     * first transmission (hopCount 0, shared placeholder MAC) is MACed per link here.
     */
    fun transmit(from: SimNode, to: NodeAddress, flight: Flight) {
        metrics.transmissions++
        from.drainBattery(batteryDrainPerTx)
        val link = linkBetween(from.id, to)
        val toNode = nodesByAddr[to]
        if (!from.up || toNode?.up != true || link == null) {
            metrics.lostTransmissions++
            return
        }
        val env = if (link.linkKey != null && flight.envelope.hopSection.hopCount == 0) {
            flight.envelope.copy(
                hopSection = flight.envelope.hopSection.copy(
                    hopMac = MeshCrypto.hopMac(link.linkKey, Codec.hopSectionRegion(flight.envelope)),
                ),
            )
        } else {
            flight.envelope
        }
        val flightOut = if (env !== flight.envelope) flight.copy(envelope = env) else flight
        if (rng.nextDouble() < link.lossFrom(from.id)) {
            metrics.lostTransmissions++
            retryOrGiveUp(from, to, flightOut)
            return
        }
        val latency = link.latencyMs(from.id, rng) + link.frameMs(flightOut.bytes())
        engine.postIn(latency.toLong().coerceAtLeast(1)) {
            onArrive(to, flightOut.copy(arrivalLink = link))
        }
    }

    private fun retryOrGiveUp(from: SimNode, to: NodeAddress, flight: Flight) {
        val ackRequired = (flight.envelope.flags and Flags.ACK_REQUIRED) != 0
        if (ackRequired && flight.attempt < 4) {
            val backoff = listOf(100L, 200L, 400L)[flight.attempt - 1]
            engine.postIn(backoff) { transmit(from, to, flight.copy(attempt = flight.attempt + 1)) }
        }
    }

    private fun onArrive(to: NodeAddress, flight: Flight) {
        val node = nodesByAddr[to] ?: return
        if (!node.up) return
        if (trackFlood) {
            floodSeen.getOrPut(hex(flight.envelope.msgId)) { mutableSetOf() }.add(node.name)
        }
        node.receive(flight)
    }

    /**
     * One source-routed hop for the return leg (inline). [from] is the CURRENT packet
     * holder (the hop link tracks the cursor). Returns true when the hop was delivered
     * AND acked. Failed attempts consume their retry backoff in virtual time
     * (100/200/400 ms), mirroring the SourceRouter policy.
     */
    fun sendHopInline(from: SimNode, to: NodeAddress, envelope: Envelope, failCount: MutableMap<String, Int>): Boolean {
        metrics.transmissions++
        from.drainBattery(batteryDrainPerTx)
        val link = linkBetween(from.id, to)
        val toNode = nodesByAddr[to]
        val latency = (link?.latencyMs(from.id, rng) ?: 10.0) + (link?.frameMs(60 + envelope.payload.size) ?: 0.0)
        engine.runInline { engine.advance(latency.toLong().coerceAtLeast(1)) }
        val ok = link != null && from.up && toNode?.up == true && rng.nextDouble() >= link.lossFrom(from.id)
        if (!ok) {
            val key = "${from.id.value}>${to.value}"
            val n = failCount[key] ?: 0
            failCount[key] = n + 1
            val backoff = listOf(100L, 200L, 400L)[n.coerceIn(0, 2)]
            engine.runInline { engine.advance(backoff) }
        }
        return ok
    }

    // ---------------- app layer ----------------

    /**
     * Originates a RIDE_REQUEST flood from [fromName]. [countAsRequest]=false marks an
     * app-level retransmission (partition heal) that must not inflate the denominator.
     */
    fun sendRequest(
        fromName: String,
        tag: String,
        ttlSec: Int = 30,
        destination: Destination = Destination(DestKind.DRIVER_ANY),
        maxHops: Int = this.maxHops,
        payload: ByteArray = "request|$tag".toByteArray(),
        noRouteAfterMs: Long = 5_000,
        onNoRoute: ((Envelope) -> Unit)? = null,
        countAsRequest: Boolean = true,
    ) {
        val origin = node(fromName)
        val env = Validator.buildEnvelope(
            type = MessageType.RIDE_REQUEST,
            flags = Flags.ACK_REQUIRED,
            destination = destination,
            payload = payload,
            maxHops = maxHops,
            ttlSec = ttlSec,
            identity = origin.identity,
            originId = origin.originId,
            originEpoch = 1,
            createdAtSec = origin.localNowSec(),
        )
        if (countAsRequest) metrics.requestsSent++
        sentAtByTag[tag] = engine.clock.nowMs()
        sentEnvelopes[tag] = env
        msgIdByTag[tag] = hex(env.msgId)
        tagByMsgId[hex(env.msgId)] = tag
        if (trackFlood) floodSeen.getOrPut(hex(env.msgId)) { mutableSetOf() }.add(origin.name)
        origin.startFlood(Flight(env, emptyList(), origin.id))
        engine.postIn(noRouteAfterMs) {
            if (tag !in answeredTags) {
                metrics.noRoute++
                origin.appStatus.add("no route for $tag")
                onNoRoute?.invoke(env)
            }
        }
    }

    fun requestTagOf(env: Envelope): String = tagByMsgId[hex(env.msgId)] ?: "untagged"

    /** Called when a QUOTE (or gateway response) reaches its destination rider. */
    fun onQuoteArrived(rider: SimNode, quote: Envelope) {
        val hex = hex(quote.msgId)
        if (!rider.appSeen.add(hex)) {
            metrics.appDuplicates++
            return
        }
        val tag = quote.payload.toString(Charsets.UTF_8).removePrefix("quote|")
        val sentAt = sentAtByTag[tag]
        if (sentAt == null) {
            // Untracked origin (e.g. spam attacker): never inflates delivery metrics.
            return
        }
        if (tag in answeredTags) { // second app-level QUOTE for an already-answered request
            metrics.appDuplicates++
            return
        }
        answeredTags.add(tag)
        metrics.quotesDelivered++
        metrics.latenciesMs.add((engine.clock.nowMs() - sentAt).toDouble())
        metrics.quoteOriginByTag[tag] = hex(quote.originId)
        rider.appStatus.add("quote received for $tag")
        quoteListeners.forEach { it(tag, engine.clock.nowMs()) }
    }

    /** Oracle cost/hop stretch against the static reverse graph (scenario 7 / stretch metrics). */
    fun recordStretch(driver: SimNode, riderAddr: NodeAddress, selected: RouteDiscovery.Selection.Selected) {
        if (!computeStretch) return
        metrics.chosenFirstHops.add(selected.best.hops.firstOrNull()?.node?.value ?: "-")
        val oracleGraph = reverseOracleGraph(driver)
        val costFn = driver.costFunction()
        val oracle = DijkstraOracle.shortestPaths(oracleGraph, driver.id, costFn, maxHops)
        val oracleCost = oracle.costs[riderAddr] ?: return
        metrics.pathCostStretch.add(selected.bestCost / oracleCost)
        val oraclePath = oracle.paths[riderAddr]
        if (oraclePath != null && oraclePath.isNotEmpty()) {
            metrics.pathStretchHops.add(selected.best.hops.size.toDouble() / oraclePath.size.toDouble())
        }
    }

    /** Graph for the Dijkstra oracle: reverse-direction edges with the same hop metrics as candidates. */
    fun reverseOracleGraph(from: SimNode): Map<NodeAddress, List<Edge>> {
        val graph = HashMap<NodeAddress, MutableList<Edge>>()
        fun push(link: SimLink, a: NodeAddress, b: NodeAddress) {
            val bNode = nodesByAddr.getValue(b)
            val snap = snapshotFor(
                link, a, b, bNode.battery.toInt(), bNode.charging,
                bNode.store.size(), 512, engine.clock.nowMs(),
                revoked = bNode.name in revokedNodes,
            )
            graph.getOrPut(a) { mutableListOf() }.add(
                Edge(
                    b,
                    PathHop(b, snap.etx, snap.batteryPenalty, snap.loadPenalty, snap.instabilityPenalty, snap.latencyMs, snap.revoked),
                ),
            )
        }
        for (link in links + radioLinks) {
            push(link, link.a, link.b)
            push(link, link.b, link.a)
        }
        return graph
    }

    /**
     * Reverse candidate (driver -> rider) from the observed forward flight:
     * reverse chain nodes = relays reversed + rider; hop j rides the same link the
     * forward path used (ETX is direction-symmetric: product of both delivery ratios).
     */
    fun reverseCandidate(flight: Flight, riderAddr: NodeAddress, nowMs: Long): CandidatePath {
        val traces = flight.pathSoFar
        val forwardLinks = traces.map { it.viaLink } + listOfNotNull(flight.arrivalLink)
        val reverseNodes = traces.reversed().map { it.node } + riderAddr
        val lastIdx = forwardLinks.lastIndex
        val metrics = com.ridehailing.mesh.routing.LinkMetrics()
        val hops = reverseNodes.mapIndexed { j, node ->
            val link = forwardLinks[lastIdx - j]
            val nodeSim = nodesByAddr.getValue(node)
            val snap = snapshotFor(
                link, NodeAddress(""), node, nodeSim.battery.toInt(), nodeSim.charging,
                nodeSim.store.size(), 512, nowMs,
                revoked = nodeSim.name in revokedNodes,
            )
            PathHop(
                node, snap.etx, snap.batteryPenalty, snap.loadPenalty,
                metrics.instabilityPenalty((nowMs - link.firstSeenMs).coerceAtLeast(0), 0.0),
                snap.latencyMs, snap.revoked,
            )
        }
        return CandidatePath(hops)
    }

    // ---------------- scenario operations ----------------

    fun setNodeUp(name: String, up: Boolean) {
        node(name).up = up
    }

    fun runUntil(horizonMs: Long) = engine.runUntil(horizonMs)

    fun floodCompletion(msgIdHex: String, reachable: Int): Double {
        val seen = floodSeen[msgIdHex]?.size ?: 0
        return if (reachable == 0) 0.0 else seen.toDouble() / reachable
    }
}

/**
 * One simulated node: routing stack (real Stage 1/2 primitives) + app behavior.
 */
class SimNode(
    private val world: SimWorld,
    val name: String,
    internal var role: SimRole,
    internal var position: Position,
    internal var battery: Double,
    internal var charging: Boolean,
    val isGateway: Boolean,
    internal var clockOffsetMs: Long,
) {
    val id = NodeAddress(name)
    val identity: KeyPair = MeshCrypto.newIdentity()
    val originId: ByteArray = MeshCrypto.sha256(name.toByteArray()).copyOf(8)
    val originIdHex = world.hex(originId)
    var up = true

    var appStatus = mutableListOf<String>()
    val appSeen = HashSet<String>()

    // mesh config (scenario 13): mutable, applied from signed bundles
    var meshConfig = MeshConfig(maxHops = world.maxHops, weights = world.weights)
    var lastAcceptedEpoch = Long.MIN_VALUE

    private val localClock: MeshClock = object : MeshClock {
        override fun nowMs(): Long = world.engine.clock.nowMs() + clockOffsetMs
    }
    private val jitter = Jitter(world.engine.seed * 31 + name.hashCode().toLong())
    private val seenCache = SeenCache()
    private val flooder = Flooder(seenCache, OriginRateLimiter(world.originRateLimitPer10s), Flooder.Config(), localClock, jitter)
    private val routeTable = RouteTable(RouteTable.DEFAULT_EXPIRY_MS, localClock)
    private val routeDiscovery = RouteDiscovery(costFunction(), routeTable, localClock)
    private val sourceRouter = SourceRouter()
    val store = StoreAndForward(localClock)
    private val lastSender = HashMap<SeenCache.Key, NodeAddress>()
    private val pendingRebroadcast = HashMap<SeenCache.Key, com.ridehailing.mesh.routing.CancelHandle>()
    private val relaysDone = HashSet<SeenCache.Key>()

    fun costFunction(): CostFunction = CostFunction(meshConfig.weights, meshConfig.maxHops)

    fun localNowSec(): Int = (localClock.nowMs() / 1000).toInt()

    fun drainBattery(percent: Double) {
        if (!charging) battery = (battery - percent).coerceAtLeast(0.0)
    }

    fun neighborCount(): Int = world.neighborsOf(this).size

    private fun isOriginSelf(env: Envelope) = env.originId.contentEquals(originId)

    private fun matchesDestination(dest: Destination): Boolean = when (dest.kind) {
        DestKind.DRIVER_ANY -> role == SimRole.DRIVER
        DestKind.DRIVER_ID -> dest.id?.contentEquals(originId) == true
        DestKind.GATEWAY_ANY -> role == SimRole.GATEWAY
        DestKind.NODE -> dest.id?.contentEquals(originId) == true
        DestKind.ANY -> false
    }

    /** Origin-side flood start: one transmission per neighbor (BLE connection semantics). */
    fun startFlood(flight: SimWorld.Flight) {
        for (n in world.neighborsOf(this)) world.transmit(this, n, flight)
    }

    // ---------------- receive pipeline ----------------

    fun receive(flight: SimWorld.Flight) {
        val env = flight.envelope
        if (world.revokedOrigins.contains(world.hex(env.originId))) {
            world.metrics.revokedDrops++
            return
        }
        val originPub = if (world.verifySignatures) world.keys[world.hex(env.originId)] else null
        val verdict = Validator.validate(
            env, meshConfig.maxHops,
            localNowSec() - world.skewToleranceSec,
            originPub,
            flight.arrivalLink?.linkKey,
        )
        if (verdict is Validator.Verdict.Invalid) {
            world.metrics.invalidDrops[verdict.reason] = (world.metrics.invalidDrops[verdict.reason] ?: 0) + 1
            return
        }

        if (env.type == MessageType.GATEWAY_ANNOUNCE && env.payload.size >= 4) {
            val gwSec = ((env.payload[0].toInt() and 0xFF) shl 24) or ((env.payload[1].toInt() and 0xFF) shl 16) or
                ((env.payload[2].toInt() and 0xFF) shl 8) or (env.payload[3].toInt() and 0xFF)
            clockOffsetMs = gwSec * 1000L - world.engine.clock.nowMs()
        }

        val key = SeenCache.Key(env.originId, env.msgId)
        val alreadySeen = seenCache.seen(key, localClock.nowMs())

        if (matchesDestination(env.destination) && env.type == MessageType.RIDE_REQUEST) {
            if (!appSeen.add(world.hex(env.msgId))) {
                world.metrics.appDuplicates++ // replay/duplicate request at the app layer
                return
            }
            onAppRequest(env, flight)
            return
        }

        if (alreadySeen) {
            if (lastSender[key] == flight.from) return // transport retry echo, not coverage
            when (val d = flooder.onReceive(env, isOriginSelf(env), neighborCount())) {
                is Flooder.Decision.Suppress -> pendingRebroadcast.remove(key)?.cancel()
                is Flooder.Decision.Drop -> recordDrop(d.reason)
                else -> {}
            }
            return
        }

        val decision = flooder.onReceive(env, isOriginSelf(env), neighborCount())
        lastSender[key] = flight.from
        when (decision) {
            is Flooder.Decision.Rebroadcast -> scheduleRebroadcast(env, flight, decision.delayMs)
            is Flooder.Decision.Drop -> recordDrop(decision.reason)
            else -> {}
        }
    }

    private fun recordDrop(reason: String) {
        if (reason == "origin_rate_limited") world.metrics.rateLimitedDrops++ else {
            world.metrics.invalidDrops[reason] = (world.metrics.invalidDrops[reason] ?: 0) + 1
        }
    }

    /** Driver/gateway app: observe reverse candidate and schedule route selection + quote. */
    private fun onAppRequest(env: Envelope, flight: SimWorld.Flight) {
        world.metrics.requestsDeliveredAtDriver++
        world.metrics.forwardPathHops.add((flight.pathSoFar.size + 1).toLong())
        val key = SeenCache.Key(env.originId, env.msgId)
        val riderNode = world.nodeByOriginId(world.hex(env.originId))
            ?: error("request origin unknown: ${world.hex(env.originId)}")
        val candidate = world.reverseCandidate(flight, riderNode.id, localClock.nowMs())
        routeDiscovery.observe(key, candidate)
        if (world.selectScheduled.add(world.hex(env.msgId))) {
            world.engine.postIn(800) { selectAndQuote(key, env, riderNode) }
        }
    }

    private fun selectAndQuote(key: SeenCache.Key, request: Envelope, riderNode: SimNode) {
        when (val sel = routeDiscovery.select(key, riderNode.id)) {
            is RouteDiscovery.Selection.Selected -> {
                world.recordStretch(this, riderNode.id, sel)
                val tag = world.requestTagOf(request)
                world.engine.postIn(400) { sendQuote(sel, request, riderNode, tag) }
            }
            is RouteDiscovery.Selection.None -> {} // rider's no-route timer reports it
        }
    }

    private fun sendQuote(sel: RouteDiscovery.Selection.Selected, request: Envelope, riderNode: SimNode, tag: String) {
        if (!up) return
        val quote = Validator.buildEnvelope(
            type = MessageType.QUOTE,
            flags = Flags.CRITICAL,
            destination = Destination(DestKind.NODE, riderNode.originId),
            payload = "quote|$tag".toByteArray(),
            maxHops = meshConfig.maxHops,
            ttlSec = 30,
            identity = identity,
            originId = originId,
            originEpoch = 1,
            createdAtSec = localNowSec(),
        )
        val failCounts = HashMap<String, Int>()
        // The source-routed packet MOVES: hop k+1 is sent by the holder after hop k.
        var cursor: SimNode = this
        val hopLink = SourceRouter.HopLink { to, _ ->
            val from = cursor
            val ok = world.sendHopInline(from, to, quote, failCounts)
            if (ok) cursor = world.nodesByAddr.getValue(to)
            ok
        }
        val res = sourceRouter.send(quote, sel.best, sel.backup, hopLink, storeAndForward = null)
        // SourceRouter semantics: Delivered = best path ok; Failed(usedBackup=true) =
        // delivered over the backup path. Both mean the rider app received the quote.
        val delivered = res == SourceRouter.SendResult.Delivered ||
            (res is SourceRouter.SendResult.Failed && res.usedBackup)
        if (delivered) world.onQuoteArrived(riderNode, quote)
    }

    private fun scheduleRebroadcast(env: Envelope, flight: SimWorld.Flight, delayMs: Long) {
        val key = SeenCache.Key(env.originId, env.msgId)
        if (key in relaysDone) return
        val handle = world.engine.postIn(delayMs) {
            pendingRebroadcast.remove(key)
            relayNow(env, flight)
        }
        pendingRebroadcast[key] = handle
    }

    private fun relayNow(env: Envelope, flight: SimWorld.Flight) {
        val key = SeenCache.Key(env.originId, env.msgId)
        if (relaysDone.contains(key)) return
        relaysDone.add(key)
        world.metrics.relaysByNode[name] = (world.metrics.relaysByNode[name] ?: 0) + 1
        if (battery < 5.0 && !charging) return // spec: exclude <5% relays
        val originPub = if (world.verifySignatures) world.keys[world.hex(env.originId)] else null
        for (n in world.neighborsOf(this)) {
            if (n == flight.from) continue
            val link = world.linkBetween(id, n) ?: continue
            val relayed = Validator.relay(
                env, meshConfig.maxHops, localNowSec(),
                id.hopIdHash(), linkQuality = 200, loadClass = 0,
                nextLinkKey = link.linkKey, originPublicKey = originPub,
            )
            when (relayed) {
                is Validator.RelayResult.Dropped ->
                    world.metrics.invalidDrops[relayed.reason] = (world.metrics.invalidDrops[relayed.reason] ?: 0) + 1
                is Validator.RelayResult.Forwarded -> {
                    world.relayedSampleCollector?.invoke(relayed.envelope)
                    world.transmit(this, n, SimWorld.Flight(relayed.envelope, flight.pathSoFar + SimWorld.HopTrace(id, flight.arrivalLink ?: error("missing arrival link")), id))
                }
            }
        }
    }

    /** Applies a signed config bundle (scenario 13). Returns Accepted or Rejected. */
    fun applyConfig(bundle: MeshConfigBundle, adminPublicKey: PublicKey): ConfigVerdict {
        val v = MeshConfigManager.verify(bundle, adminPublicKey, localNowSec(), lastAcceptedEpoch)
        if (v is ConfigVerdict.Accepted) {
            meshConfig = bundle.config
            lastAcceptedEpoch = bundle.epoch
        }
        return v
    }
}

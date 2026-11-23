# PART C: MESH SPEC (OFFLINE BLUETOOTH MULTI-HOP BOOKING)

> Saved from the master autonomous build prompt on 2026-11-23. Authoritative source: /home/z/my-project/upload/Pasted Content_1795430967449.txt. Precedence per prompt header: A overrides communication rules; C wins over B on mesh details; D wins on automation; E wins on reporting/visibility.

# PART C: MESH SPEC (OFFLINE BLUETOOTH MULTI-HOP BOOKING)
################################################################################

# ROLE
Distributed-systems and Android engineer. Build the offline mesh subsystem. This part is authoritative for mesh internals. Where unspecified, decide, record in DECISIONS.md, continue.

# GOAL
A rider with no internet must be able to (1) see nearby on-duty drivers, (2) request a ride, (3) receive an authoritative server-signed final price, (4) confirm, (5) get trip updates, all over Bluetooth, directly or by hopping through other opted-in riders and drivers up to an admin-configurable max hop count. The return path for price and confirmations must be the best available path, with automatic failover.

# REALITY CHECK (honest version in /docs/MESH_LIMITS.md)
- BLE range is roughly 10-100 m, much less indoors or in traffic. Multi-hop works only where enough opted-in devices are dense and slow-moving. Say so in docs and in the diagnostics screen.
- Android background limits, Doze, OEM battery killers (Xiaomi, Huawei, Samsung, Oppo...) can stop scanning/advertising. Use a foreground service, document per-OEM instructions, show a "mesh health" indicator.
- Legacy BLE advertisements carry ~31 bytes; extended advertising (BLE 5) is not universal. Design for both.
- Emulators cannot test real Bluetooth. Only SimulatedMeshTransport runs on emulators/CI. Never claim BLE verification without physical devices.

# MODULE LAYOUT
/mesh-core (pure Kotlin/JVM, zero Android imports): protocol (envelope, codecs, fragmentation, versioning), crypto (interfaces + Tink/libsodium-JVM), routing (seen-cache, flooding, route discovery, path scoring, route table, failover), store (store-and-forward queue, replay cache; Room impl in android), transport (`MeshTransport` + `SimulatedMeshTransport`), sim (topology builder, mobility, loss/latency models, scenario runner, metrics, Dijkstra oracle).
/android/mesh-android: BLE transport, Nearby transport, foreground service, permissions, diagnostics UI, Room persistence.
/backend mesh-gateway: signature verification, request ingestion, quote relay, admin-only mesh tables.

# STAGES (each has a gate; do not proceed until it passes)

## Stage 1: Protocol and crypto (mesh-core)
Envelope (binary, CBOR or protobuf). Immutable header (covered by origin signature): ver (u8), type (u8), flags (u8: ack-required, critical, fragment, encrypted), msgId (16 random bytes), originId (8 bytes rotating pseudonym), originEpoch (u32), dest kind (DRIVER_ANY | DRIVER_ID | GATEWAY_ANY | NODE) + optional 8-byte id, createdAt (u32 unix s), ttlSec (u16), maxHops (u8; stamped by origin from signed config, validated by relays against their own signed config, using the min), payloadLen (u16), payload (E2E ciphertext + AEAD tag), originSig (Ed25519, 64 bytes).
Mutable hop section (not covered by origin signature; per-hop MAC between neighbors): hopCount (u8), breadcrumbs (up to maxHops entries: 4-byte hashed hop ID + 1-byte quantized link quality + 1-byte relay load/battery class), hopMac.
Rules: relays may only increment hopCount and append their own breadcrumb. Reject hopCount > maxHops, expired ttl, bad signature, replayed (msgId, originId). Unknown ver: ignore gracefully, never crash.
Types: DRIVER_BEACON, RIDE_REQUEST, REQUEST_ACK, QUOTE, QUOTE_ACCEPT, QUOTE_DECLINE, DRIVER_OFFER, TRIP_UPDATE, PRESENCE, GATEWAY_ANNOUNCE, GATEWAY_RELAY, ROUTE_REQUEST, ROUTE_REPLY, ROUTE_ERROR, HOP_ACK, PING, CONFIG_SYNC, REVOCATION_SYNC.
Fragmentation/reassembly for BLE MTU (20-185 usable bytes, negotiate up): fragment header (msgId hash, index, total), reassembly timeout, memory cap, per-origin anti-DoS limits. Property-test random messages and fragment orders/losses.
Crypto: each device has long-term Ed25519 signing and X25519 keys registered with the backend after login (bound to user and device attestation where available); the backend is the trust anchor. Rotating originId = truncated HMAC(deviceSecret, epoch), rotation admin-configurable (default 15 min). Payload E2E rider<->backend using X25519 + ChaCha20-Poly1305 (sealed-box style with sender signature); relays and relaying drivers never read payloads. Signed config bundle (max hops, TTL, weights, rotation, revocation list) verified with the backend's pinned public key, anti-rollback version, safe default if none (maxHops=3, ttl=120 s). Revocation list synced via CONFIG_SYNC/REVOCATION_SYNC when any node has internet; nodes drop envelopes from revoked originIds. Replay protection: timestamp window + persistent seen-cache; tolerate clock skew via backend-signed time hint in GATEWAY_ANNOUNCE. Never broadcast phone numbers, names, or user IDs.
GATE 1: all codec/crypto tests pass; fuzz tests (malformed, truncated, oversized, version-mismatched) cause no crash or memory blowup; known-answer tests for signatures/AEAD; flipping any immutable-header bit -> reject.

## Stage 2: Routing and path scoring (mesh-core)
Forward leg: controlled flooding with seen-cache dedup, hop limit, ttl, jittered rebroadcast (random 20-200 ms scaled by neighbor count), counter-based suppression (skip rebroadcast if k >= 2 duplicates heard in the window; k configurable), per-origin rate limits. Gradient bias: nodes with a known route to a gateway/driver forward unicast first, fall back to scoped flood. Each relay appends a breadcrumb with measured link quality to the previous hop and its own load/battery class.
Route discovery (AODV-style): the destination (driver app or gateway acting for the server) collects copies of the same msgId over different paths during a collection window (default 800 ms, max 2 s). Score each candidate (lower is better):
 cost = w_hop*H + w_etx*sum(ETX_i) + w_batt*sum(battPenalty_i) + w_load*sum(loadPenalty_i) + w_stab*sum(instabilityPenalty_i) + w_lat*latencyMs/100 + w_gw*gatewayStalenessPenalty
 - H = hops. ETX_i = 1/(deliveryFwd_i * deliveryRev_i), EWMA alpha 0.3, clamped [1,10]; initial from RSSI mapping.
 - battPenalty: 0 if charging or >50%; 1 if 20-50%; 3 if 10-20%; 8 if <10%; exclude relays <5% unless no alternative.
 - loadPenalty = queueLength/queueCapacity*2.
 - instabilityPenalty: 0 if link seen continuously >60 s; up to 3 for links first seen <5 s ago or high RSSI variance.
 - gatewayStalenessPenalty = secondsSinceLastGatewayAnnounce/30, capped at 3.
 Default weights: w_hop 1.0, w_etx 1.5, w_batt 0.8, w_load 0.6, w_stab 0.8, w_lat 0.3, w_gw 0.5; all from signed admin config. Hard-reject paths containing a revoked node. Keep best path + one node-disjoint-as-possible backup (ties by cost). Route table expiry default 60 s, refreshed by traffic and PING.
Return leg (QUOTE, QUOTE_ACCEPT, ACK, OFFER, UPDATE): source-routed along the best path. Per-hop HOP_ACK with retry (3 tries, backoff 100/200/400 ms). On hop failure the relay sends ROUTE_ERROR; sender switches to the backup path; then scoped flood bounded by remaining hop budget; final fallback store-and-forward until ttl. Critical messages (QUOTE, QUOTE_ACCEPT) may go over the top-2 paths in parallel (admin toggle), receiver dedups by msgId. Mobility: if a link drops or ETX degrades past a threshold, proactively ROUTE_REQUEST/PING before the next critical send. Internet shortcut: if any node on the path (or the rider) has internet, the backend may return the response through that gateway (GATEWAY_RELAY) and the mesh covers only the remaining hops; prefer fewest mesh hops when scores are within 10%. Everything is store-and-forward with expiry and delivery receipts; surface status in UI ("Searching, 2 hops away", "Price received", "Waiting for a relay").
Beacons: DRIVER_BEACON every beaconIntervalSec (default 5 s, admin-configurable, adaptive when many nodes heard): rotating ID, quantized position (~10 m grid), ride-type bitmap, availability flag, key epoch, short MAC. Signed profile snippet (name, rating, vehicle summary, photo hash) fetched over a short GATT read or relayed once and cached with expiry; verify against backend-issued driver certificates. PRESENCE from offline riders (coarse position, rotating ID) relayed to gateways every presenceIntervalSec (default 30 s), encrypted to the backend only.

## Stage 3: SimulatedMeshTransport and scenario suite (CI)
Simulator supports arbitrary graphs, per-link loss/latency/jitter/bandwidth, mobility (random waypoint and scripted), churn, battery drain, clock skew, MTU limits; deterministic seeds; metrics (delivery ratio, latency p50/p95, transmissions per delivered message, path stretch, failover time). Include a Dijkstra oracle over the same cost function on static snapshots.
Mandatory scenarios and thresholds (propose better numbers with justification, never weaker without reason):
1. Line, 1..maxHops hops, lossless: 100% delivery both ways, correct hopCount.
2. Line beyond maxHops: 0% forward delivery, no relay beyond limit, rider gets a clean "no route".
3. Star and grid (50-200 nodes): flood completes; transmissions per message <= 2.5 x nodes in range; app-layer duplicates = 0.
4. Ring/loop: no packet circulates more than once per node.
5. Broadcast storm (200 nodes, 20 simultaneous requesters): total transmissions <= 3x single-request baseline per request; delivery >= 95%.
6. Lossy links (10/30/50% per hop) over 3 hops: delivery >= 99/95/80% with retries; no crash; report p95 latency.
7. Static multi-path: chosen return path cost equals the Dijkstra optimum in 100% of 1000 seeded random topologies; backup is node-disjoint when one exists.
8. Relay failure mid-reply: kill the relay holding the in-flight QUOTE; delivery via backup within 3 s, no duplicate at the app.
9. Mobility (walking and driving speeds): request-to-quote success >= 90% at max 3 hops in a dense scenario; report degradation vs density.
10. Partition and heal: queued messages deliver after healing within ttl; expired ones dropped and reported.
11. Gateway churn: route switches to another gateway or queues; no loss within ttl if any gateway returns.
12. Malicious nodes (replays, forged signatures, tampered hop section, single-origin spam, revoked key): all dropped; rate limits hold; honest delivery drop <= 5%.
13. Config change: new signed bundle with lower maxHops applies live; stale/rolled-back bundles rejected.
14. Clock skew +/-5 min: handled per policy, no crash, time hint corrects.
15. Fragmentation under loss/reordering: reassembly succeeds or times out cleanly with bounded memory.
GATE 3: all scenarios run in CI on every commit with fixed seeds; results to /docs/evidence/mesh-sim/ (metrics table + charts); baseline stored in baseline.json; build fails if any metric regresses >10%.

## Stage 4: Android integration (/android/mesh-android)
- `BleMeshTransport`: advertising (extended where supported, legacy fallback), scanning with filters and duty cycling, GATT server/client for profile snippet and large payloads, MTU negotiation, connection pool limits, reconnect/backoff, address-rotation awareness.
- Optional `NearbyMeshTransport` (Nearby Connections, P2P_CLUSTER). Choose per capability, document a measured comparison in DECISIONS.md.
- Foreground service (type connectedDevice), notification, permission flows for Android 8-15 (BLUETOOTH_SCAN with neverForLocation where possible, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, location for older versions, POST_NOTIFICATIONS, battery-optimization prompt with OEM guidance).
- Persistence: seen-cache, route table, store-and-forward queue, key material (Android Keystore) survive process death.
- Consent and settings: separate toggles "Use Offline Network" (rider), "Accept offline riders" and "Relay for other riders" (driver), rider relay opt-in; data-use/battery explanations; per-day relay cap; relay-only-while-charging.
- Diagnostics screen: nearby nodes (count, RSSI), hop budget, queue size, last routes, mesh health, log export. Drivers see only aggregate counters, never per-rider data.
- Rider offline UX: cached map, nearby driver markers from beacons, "any driver" or tap a driver, estimate with not-final note, request status timeline, final quote card with expiry countdown and difference-from-estimate note, confirm/decline, trip screen with updates "while in range" and clear stale-data indication.
GATE 4: instrumented tests on emulators using the simulated transport (2-4 emulators via the host sim bridge); screenshots light/dark, RTL, large font; process-death and permission-revocation tests pass.

## Stage 5: Gateway, backend, driver blindness
- Gateway handler (driver or rider app with internet): accepts GATEWAY_RELAY/RIDE_REQUEST envelopes, forwards opaquely over HTTPS/WSS with the original signature, returns server responses into the mesh along the best known path. A gateway never decrypts payloads and never learns user identity.
- Backend `mesh-gateway`: verifies signatures and key epochs, rejects revoked/replayed requests, decrypts, creates a normal trip through the same internal service as the online path, computes and signs the authoritative quote (validity window), returns QUOTE via best gateway/path, processes QUOTE_ACCEPT (wallet hold, dispatch), emits TRIP_UPDATEs, records everything in admin-only tables: origin channel, gateway id, hop count, path, per-hop timings, retries, failovers.
- Driver blindness: dispatch produces byte-for-byte identical driver-facing payload shapes for online and offline-origin trips. No origin field; normalize response timing with a minimum-delay and jitter policy applied equally to both origins; identical notification text, sound, ordering. Driver tokens cannot query mesh tables (RBAC tests). The gateway driver never learns whether the request they relayed became a trip assigned to them.
- Tests: contract test comparing JSON schemas and field sets (online vs offline origin) must be identical; UI screenshot diff; log scan for origin leakage; timing test showing statistically indistinguishable distributions (document method and residual risk).
- Admin: mesh map layers (offline riders from PRESENCE, nodes, links, animated request/quote paths with chosen and backup highlighted, partitions), trip detail with full mesh timeline, time-slider replay, mesh analytics, config editor with validation and rollback, revocation tools.
GATE 5: end-to-end on emulators with simulated transport: offline rider -> 1, 2, 3 hops -> gateway driver -> backend -> a different assigned driver -> quote returns via best path -> accept -> trip completes. Admin sees the whole path; the assigned driver's app shows no sign of offline origin. DB rows, audit log, event hash chain checked.

## Stage 6: Physical device validation (required before claiming BLE works)
Minimum 3 physical Android devices (different OEMs/versions): A offline rider, B relay rider, C driver/gateway with internet. Write /docs/MESH_DEVICE_TEST_PLAN.md and run it: discovery range and RSSI curves (open air, indoors, in a car); 1-, 2-, 3-hop round trips (record how hops were forced); failover (switch off relay mid-reply, walk out of range and back); screen-off/Doze behavior, battery drain per hour, OEM killers; Android 12+ permission flows, Bluetooth toggled mid-session, location off, airplane mode with Bluetooth on; concurrency; security (BLE sniffer proves no plaintext identity/payload and ID rotation). Store results/videos/logs in /docs/evidence/mesh-device/. If devices are unavailable, say so, list exactly what is unverified, mark mesh "simulation/emulator-verified only". Do NOT claim BLE verification.

## Stage 7: Hardening
Battery/performance budgets (measure beaconing mAh/hour against a target), memory caps, rate-limit tuning, chaos tests, protocol versioning/upgrade test (v1 and v2 nodes coexisting), threat model update (rooted drivers, sniffers, Sybil/relay-flooding, GPS spoofing in beacons, replay, key theft), docs (MESH_PROTOCOL.md, MESH_LIMITS.md, MESH_RUNBOOK.md, admin tuning guide), legal/privacy note on location sharing by offline riders.

# MESH VERIFICATION
Apply Part B's Verification Protocol to every mesh feature, plus: each stage gate passed with evidence before the next; the full simulation suite runs on every commit and a failing scenario blocks merge; inspect DB rows, event hash chain and admin views for each end-to-end scenario; report simulation, emulator, and physical-device results separately.

################################################################################

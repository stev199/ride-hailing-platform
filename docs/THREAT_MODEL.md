# THREAT_MODEL

v0.1 (Phase 0). STRIDE-based, per component, plus mesh-specific analysis and driver-blindness residual risks. Updated at each phase gate (required at Stage 7).

## Assets

- PII: identities, phone numbers, precise locations (live + visited places), documents (ID, license, insurance).
- Money: wallet balances, ledger integrity, fare config, promo codes.
- Safety: rider/driver live location, trip state, SOS data.
- Platform integrity: audit/event store, signed config bundles, RBAC, admin sessions.
- Mesh privacy: offline riders' existence/position, rotating pseudonyms, relay payloads.

## Trust boundaries

1. Internet clients <-> backend API (untrusted; JWT).
2. Mesh BLE links <-> backend (gateways relay opaquely; signatures verified at mesh-gateway only).
3. Admin browser <-> API (RBAC, 2FA, IP allowlist).
4. Device <-> OS (battery killers, rooted devices, screen capture).
5. CI <-> repo (secrets exposure, fork PRs — no secrets to fork PRs).

## STRIDE by component

| Component | Threats | Mitigations (land where noted) |
|---|---|---|
| API | Spoofing (stolen tokens), tampering (client-side state), repudiation (undocumented admin edits), info disclosure (over-fetch), DoS (rate-less endpoints) | JWT+rotating refresh, argon2id, server-side validation of every write, hash-chained audit (S-02/B-04), schema-limited DTOs, rate limits + Redis buckets (S-01) |
| OTP/auth | SIM-swap/OTP interception, brute force, enumeration | 6-digit hashed OTPs, 5-min expiry, attempt+rate limits (S-01), pluggable providers so weak channels can be disabled by admin (A-10) |
| Wallet/ledger | Double-entry violations, race on transfers, admin abuse of manual credit | DB constraints + serializable transfers, invariant tests (debits=credits) on every mutation path (B-08), manual ops require reason + audit (A-09) |
| Admin | Session theft, privilege escalation, PII over-access | TOTP 2FA, server-side RBAC on every endpoint (A-01), audited read-only impersonation (A-04), IP allowlist option (A-13) |
| Documents | Leakage, tamper, unauthorized view | Encrypted at rest (S-03), role-gated viewer, watermark + access audit (A-05) |
| Geo stack | Tile/proxy abuse, Nominatim overload | Internal-only exposure via compose network, caching, rate limits (M-01 runbook) |
| WS | Cross-tenant subscription, replay of events | Topic authz (own trips only), per-connection cursor+signature (B-10) |
| CI/CD | Secret leak, artifact tampering, fork PR jobs | No secrets in logs/repo, explicit least-privilege `permissions:`, fork-PR guards on secret-using jobs, SBOM + Trivy (D-part) |

## Mesh-specific threats (Part C Stage 7 also updates this)

| Threat | Analysis | Mitigation |
|---|---|---|
| Sybil nodes (fake relay identities flooding) | Spurious relays degrade routing | Ed25519 identities registered with backend; rate limits per origin; path scoring penalizes unstable/unknown links (X-02); relays <5% battery excluded unless no alternative |
| Relay flooding / resource exhaustion | Battery/memory drain on opted-in nodes | Per-origin anti-DoS limits, seen-cache caps, per-day relay cap + relay-only-while-charging options (X-04) |
| Replay of envelopes | Re-booking/re-quote replay | timestamp window + persistent seen-cache keyed (msgId, originId); mesh-gateway rejects replays (B-12) |
| Tampering with hop section | Redirect or loop traffic | Per-hop MAC between neighbors; relays verify; invalid -> drop (X-01) |
| Tampering with immutable header | Impersonation | Origin Ed25519 signature over immutable header; bit-flip KATs (GATE 1) |
| Payload eavesdropping (relays/gateways) | Learn booking details | E2E X25519+ChaCha20-Poly1305; only rider<->backend can decrypt (X-01) |
| Traffic analysis (metadata) | Infer origin/destination patterns | Rotating originId (HMAC truncation, 15-min default, admin-config), coarse quantized positions (~10 m grid) in beacons, PRESENCE encrypted to backend only |
| GPS spoofing in beacons | Fake driver positions | Driver identity cert binds position claims; backend cross-checks vs on-duty GPS stream; admin fraud flags (B-13) |
| Key theft (compromised device) | Impersonate node, decrypt payloads | Keys in Android Keystore; remote REVOCATION_SYNC propagation; mesh-gateway rejects revoked originIds (X-01) |
| Config rollback attack | Raise maxHops/re-enable revoked keys | Signed bundles + anti-rollback version check; safe defaults (maxHops=3, ttl=120s) if unsigned/unverified |
| BLE sniffer | Capture advertisements | Design assumes open-air capture: rotating IDs, no plaintext identity in beacons; verified by sniffer test in Stage 6 plan |
| Malicious relay dropping critical messages | Quote never arrives | Store-and-forward with expiry, top-2 parallel paths for QUOTE/QUOTE_ACCEPT (admin toggle), backup path failover + ROUTE_ERROR (X-02) |

## Driver-blindness residual risks (honest; per Part B/C)

- Rooted driver device or instrumented app could inspect memory/timing beyond DTOs. Mitigation: identical payload shapes and notification timing (min-delay+jitter applied equally to both origins), no origin field anywhere in driver-facing paths; RBAC tests prove driver tokens cannot query mesh tables. Residual: rooted-device introspection remains possible; accepted and documented.
- BLE sniffing near the gateway could correlate timing of relayed vs assigned requests. Residual: partial correlation possible; documented.
- Timing side-channels: dispatch latency distributions compared statistically in CI (Stage 5 tests); not a proof, a guardrail.
- Logs: automated log-scan test for origin leakage strings on driver-visible logs.

## Open items

- T-1: Confirm legal/privacy note for offline riders' approximate location visibility to platform operator (consent screen copy) — before Stage 4.
- T-2: Choose Tink vs libsodium-JVM for mesh-core crypto (performance + dependency size) — at Stage 1 start.
- T-3: BLE extended-advertising fallback matrix per OEM — Stage 4 measurement.

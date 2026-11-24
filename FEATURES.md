# FEATURES

Master feature checklist. Sources: master build prompt (Part B roles + Feature Discovery Mandate) and /docs/COMPETITIVE_AUDIT.md.

Status: `todo` | `doing` | `done` | `blocked` (reason in BLOCKERS.md) | `deferred` (reason below).
Verification levels for mesh features: `sim` (simulation), `emu` (emulator via SimulatedMeshTransport), `phys` (physical device). Never claim a level not actually achieved.
Tests: `U` unit, `I` backend integration, `C` contract, `UI` instrumented UI, `E2E` cross-actor, `PW` Playwright, `S` screenshot variants, `A` axe a11y.
Phase mapping follows the Part B delivery plan (0-11).

## Rider app (android, mode: rider)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| R-01 | P0 | Onboarding consent screens (location, notifications, mesh opt-in) | UI,S | 2 | todo | |
| R-02 | P0 | Signup/login via admin-enabled methods (OTP adapters, phone+password, email+password) | U,I,C,UI,S | 1/2 | todo | |
| R-03 | P0 | Profile: photo, name, emergency contacts | U,UI,S | 2 | todo | |
| R-04 | P0 | Saved places (home/work/custom) | U,UI,S | 2 | todo | |
| R-05 | P0 | Language switch EN/FR/AR (full RTL), dark mode, font scale, accessibility settings | U,UI,S | 2 | todo | |
| R-06 | P0 | Privacy center: data export/delete, location-history controls | I,UI,PW | 9 | todo | |
| R-07 | P0 | Main screen: full-screen MapLibre map, current location, search bar, bottom sheet, live nearby driver markers (smooth movement) | U,UI,S | 2 | todo | |
| R-08 | P0 | Place search: online (Photon/Nominatim), offline (Room FTS over visited/saved), recents | U,I,UI,S | 3 | todo | |
| R-09 | P0 | Driver marker tap: profile card (name, photo, rating, vehicle, ETA, price estimate, trips, languages, "Request this driver") | U,UI,S | 4 | todo | |
| R-10 | P0 | Booking: pickup/dropoff, multi-stop, route preview, admin-configurable ride types, estimate with disclaimer, promo, payment method, notes | U,I,UI,E2E,S | 4 | todo | |
| R-11 | P0 | Dispatch: specific-driver request or auto-dispatch (strategy/timeout/radius from config) | I,E2E | 4 | todo | |
| R-12 | P0 | Live trip: driver tracking, ETA, in-app chat, trip share link, SOS, safety check-ins, pickup PIN, route-deviation alerts | U,I,UI,E2E,S | 8 | todo | |
| R-13 | P0 | Call masking / VoIP between rider and driver | I,UI | 8 | todo | |
| R-14 | P0 | Cancel with reasons + admin fee rules + free window; rating, tip, report issue, lost item, PDF receipt | U,I,UI,E2E | 8 | todo | |
| R-15 | P0 | Trip history (filterable) + rebook | U,UI,S | 8 | todo | |
| R-16 | P0 | Wallet: balance, top-up (voucher/cash agent/admin credit), send points/money by phone/username/QR, request money, history, PIN confirmation | U,I,UI,E2E | 7 | todo | |
| R-17 | P1 | Referrals, promotions, notification center + preference center, support tickets/chat, FAQ, legal texts | I,UI | 8/9 | todo | |
| R-18 | P1 | Schedule for later (dispatch window, edit/cancel, reminders) + concurrent trips UI (admin limit) | U,I,UI,E2E | 8 | todo | |
| R-19 | P1 | Ride-for-someone-else (book with different passenger name/phone) | U,I,UI | 8 | todo | |
| R-20 | P1 | Favorite/blocked drivers | U,I,UI | 8 | todo | |
| R-21 | P1 | Split fare between riders | U,I,UI | 8 | todo | |
| R-22 | P2 | Women rider/driver preference (admin-enabled region config) | U,I | 8 | todo | |
| R-23 | P2 | Accessibility vehicles, child seat, pet-friendly options | U,I | 8 | todo | |
| R-24 | P2 | Widget / quick tile, app shortcuts, deep links (am-verified) | UI | 9 | todo | |
| R-25 | P1 | Data-saver / low-end device mode | U,UI | 11 | todo | |
| R-26 | P0 | Offline Network settings + diagnostics screen (nodes, RSSI, hop budget, queue, health, log export) | U,UI,S | 4 | todo | |

## Driver app (android, mode: driver)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| D-01 | P0 | Multi-step onboarding: personal info, vehicles, documents w/ expiry (ID, license, registration, insurance, selfie, vehicle photos), status tracker + rejection reasons + re-upload | U,I,UI,E2E,S | 5 | todo | |
| D-02 | P0 | Duty toggle + heading-home + destination filter | U,UI | 5 | todo | |
| D-03 | P0 | Incoming request with countdown, accept/decline, driver-blind parity (no origin info) | U,I,C,UI,E2E | 5/10 | todo | |
| D-04 | P0 | In-app turn-by-turn (OSRM instructions + TTS), pickup PIN, start/stop, waypoints, wait-time charge | U,I,UI,E2E | 5 | todo | |
| D-05 | P0 | Trip lifecycle end: complete, cancel reasons, receipt handoff | UI,E2E | 5 | todo | |
| D-06 | P0 | Earnings: daily/weekly, per-trip breakdown, commission, cash vs wallet, payouts | U,I,UI,S | 7 | todo | |
| D-07 | P0 | Ratings + acceptance rate | U,UI | 5 | todo | |
| D-08 | P0 | Document-expiry alerts + vehicle management | U,UI | 5 | todo | |
| D-09 | P0 | Always-online duty: connection-loss banner, degraded state, grace period -> auto off-duty (admin-configurable) | U,I,E2E | 10 | todo | |
| D-10 | P0 | Settings: "Accept offline riders" and "Relay for other riders" opt-ins; rider-blind driver UI (no origin, no per-rider data) | U,UI,C | 4/10 | todo | |
| D-11 | P1 | Demand heatmap, surge zones, quests/incentives/tiers | I,UI | 9 | todo | |
| D-12 | P2 | Fatigue limits (driving-hours enforcement) | U,I | 11 | todo | |
| D-13 | P2 | Crash detection (sensor-based safety stop) | U | 11 | todo | |

## Admin dashboard (admin-web)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| A-01 | P0 | RBAC roles: Super Admin, Operations, Support, Finance, Verifier, Read-only, custom roles; server-side enforcement | I,C,PW | 1/6 | todo | |
| A-02 | P0 | Admin shell + login + TOTP 2FA + sessions | I,PW | 1 | partial | shell+login v0 done (no TOTP yet; admin session views land with P1-4b/P2): 18 vitest + 30 playwright green, axe 0 violations |
| A-03 | P0 | Live map: online riders, drivers by status, active trips, OFFLINE riders (last known, hops, gateway, path), mesh nodes/links, filters, detail click, force-offline, message | I,PW,E2E | 6/10 | todo | |
| A-04 | P0 | User management: search/create/edit riders/drivers/admins, CSV import/export, audited read-only impersonation, reset password, force logout, sessions/devices | I,PW | 6 | todo | |
| A-05 | P0 | Verification center: document queue, zoom/rotate viewer, per-doc approve/reject + reason, expiry tracking, re-verification | I,PW,E2E | 6 | todo | |
| A-06 | P0 | Bans/suspensions (temporary/permanent), device/phone blocklist, appeals | I,PW,E2E | 9 | todo | |
| A-07 | P0 | Messaging: targeted push/in-app/OTP-channel by role/city/segment/user, templates, scheduling, delivery stats | I,PW | 9 | todo | |
| A-08 | P0 | Trips: live + historical, full timeline, route replay, edit/cancel/refund, disputes, manual assignment, fraud flags, admin-only "Origin: online / offline-mesh" + mesh path | I,PW,E2E | 6/10 | todo | |
| A-09 | P0 | Money: fares per city/ride type (base, per km/min, minimum, booking/cancel/wait fees, night/peak, surge), commissions, taxes, promos, ledger viewer, manual credit/debit (reason), payout approvals, points rules/limits/fees, reports, reconciliation, exports | U,I,PW | 7 | todo | |
| A-10 | P0 | Config center: hot-reload, versioned, rollback, signed device bundles (login methods, OTP providers, mesh settings, cache limits, matching, scheduling, concurrent trips, cancellation, ride types, service-area polygon editor, feature flags, min app version, maintenance mode, legal, branding) | I,PW | 1/6 | partial | v0 shell page + empty state (P1-4); endpoints land with P1-5a |
| A-11 | P0 | Analytics: DAU/MAU, funnels, heatmaps, cancellations, driver performance, revenue, mesh stats | I,PW | 9 | todo | |
| A-12 | P0 | Support inbox with SLA timers | I,PW | 9 | todo | |
| A-13 | P0 | Security center: audit log viewer, TOTP, IP allowlist, sessions, rate limits, fraud signals | I,PW | 9 | todo | |
| A-14 | P0 | System health dashboard | PW | 9 | todo | |
| A-15 | P0 | GDPR requests queue + export/delete execution | I,PW | 9 | todo | |
| A-16 | P0 | Lost-and-found workflow | I,PW | 8 | todo | |
| A-17 | P0 | Safety incident workflow | I,PW | 9 | todo | |
| A-18 | P1 | Corporate accounts, gift vouchers, tax invoices, insurance claims, dispute center, surveys | I,PW | 8/9 | todo | |
| A-19 | P1 | Passes/subscriptions, loyalty tiers | I,PW | 9 | todo | |

## Backend / platform

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| B-01 | P0 | Ktor backend skeleton, health, config service (signed bundles, versioned, rollback) | U,I,C | 1 | done | run 45171484716; commit cee6c2f |
| B-02 | P0 | Auth: OTP providers (Telegram bot, SMTP, self-hosted SMS gateway; optional WhatsApp doc'd), phone/email+password, JWT + rotating refresh, argon2 | U,I,C | 1 | done | backend 53 tests green (auth 37 new); dev-inbox/telegram/SMS/SMTP adapters, HS256 + rotation w/ reuse detection, argon2id, rate limits; openapi contract updated |
| B-03 | P0 | Users, roles, RBAC enforcement, sessions/devices | U,I,C | 1 | todo | |
| B-04 | P0 | Append-only hash-chained event store (trips, pings, mesh metadata, quotes, wallet, config, admin actions, docs, logins, bans) + retention + search/export + GDPR anonymization w/ legal hold | U,I | 1/9 | doing | core chain done (run 45171484716); persistence+GDPR pending |
| B-05 | P0 | PostgreSQL + PostGIS schema + Redis; migrations up tested (Flyway community = up-only, see DECISIONS D33) | I | 1 | doing | V1 schema + CI postgres run 45171484716 |
| B-06 | P0 | Dispatch engine: auto-dispatch strategy/timeout/radius expansion, manual assignment, concurrent-trip limit | U,I,E2E | 4 | todo | |
| B-07 | P0 | Fare engine: server-side final signed quotes w/ validity window + policy (upfront or metered-with-cap); parity with on-device estimator within stated tolerance | U,C | 4 | todo | |
| B-08 | P0 | Double-entry wallet/points ledger; invariant debits=credits tested; holds at quote confirmation | U,I | 7 | todo | |
| B-09 | P0 | PaymentProvider interface (cash + internal ledger default; gateway adapter slot) | U,I | 7 | todo | |
| B-10 | P0 | WebSocket gateway: events catalog, auth, reconnect/backoff, per-actor topics | I,C | 2 | todo | |
| B-11 | P0 | Push: UnifiedPush/ntfy self-hosted; optional FCM adapter | I | 9 | todo | |
| B-12 | P0 | mesh-gateway service: signature/epoch verification, replay+revocation rejection, decrypt, trip creation via same internal path as online, signed quote relay, QUOTE_ACCEPT processing, admin-only mesh tables (origin, gateway, hops, path, timings) | U,I,C,E2E | 10 | todo | |
| B-13 | P1 | Fraud signals: GPS-spoofing detection, device binding, velocity checks | U,I | 11 | todo | |
| B-14 | P2 | Optional self-hosted Sentry/GlitchTip + Prometheus/Grafana compose profiles | PW | 11 | todo | |

## Maps / geo (self-hosted, ODbL-attributed)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| M-01 | P0 | docker-compose geo stack: tileserver (PMTiles/MBTiles), OSRM, Nominatim/Photon | I (CI compose) | 0 | todo | |
| M-02 | P0 | MapLibre Android main screen w/ self-hosted vector tiles + style, smooth 60fps markers | UI,S | 2 | todo | |
| M-03 | P0 | VisitedPlace store (Room, encrypted at rest): every viewed/searched/picked/traveled/dwelled place, opt-out setting, optional E2E-encrypted sync | U,I,UI | 3 | todo | |
| M-04 | P0 | Tile caching: per-place radius+zoom auto-cache, home-city base pack, corridor caching along routes, LRU eviction, size caps (user+admin), Wi-Fi-only option | U,I,UI | 3 | todo | |
| M-05 | P0 | Offline Maps screen: regions, storage usage, pin-forever, delete, manual city download | UI,S | 3 | todo | |
| M-06 | P0 | Offline search (FTS) + offline reverse-geocode for visited places + cached recent routes | U,UI | 3 | todo | |
| M-07 | P0 | Airplane-mode verification: cached render, search/markers work, graceful uncached placeholder (adb evidence + screenshots) | UI,S | 3 | todo | |
| M-08 | P0 | Map layers: visited (cluster/heat), saved, nearby mesh drivers, toggles | U,UI,S | 3/4 | todo | |

## Pricing (estimate vs final)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| P-01 | P0 | Signed versioned fare-config bundle cached on device (expiry + stale flag) | U,I | 4 | todo | |
| P-02 | P0 | On-device estimator: best distance (cached route -> offline routing -> straight-line x calibrated detour), range w/ permanent "not the final price" note, ride-type breakdown, surge when known | U,UI,S | 4 | todo | |
| P-03 | P0 | Server final quote: signed, validity window, upfront-locked or metered-with-cap; rider sees difference vs estimate and confirms/declines; travels back over mesh for offline riders | U,I,E2E | 4/10 | todo | |
| P-04 | P0 | Fare calculator unit suite + server/on-device parity tests within tolerance | U | 4 | todo | |

## Offline mesh (Part C; authoritative)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| X-01 | P0 | Stage 1 protocol: binary envelope, immutable header + origin sig (Ed25519), mutable hop section + per-hop MAC, fragmentation, 18 message types, rotating originId, E2E X25519+ChaCha20-Poly1305 sealed box, signed config bundle + anti-rollback (backend side); GATE 1 fuzz/KAT/bit-flip all green | U | 1b | done | mesh run 45172363159; replay seen-cache + revocation sync land with Stage 2 (X-02) |
| X-02 | P0 | Stage 2 routing: controlled flooding (jitter, suppression, rate limits), breadcrumbs, AODV-style discovery, cost function (hop/ETX/battery/load/stability/latency/gateway staleness) + Dijkstra oracle, source-routed return with HOP_ACK + ROUTE_ERROR + backup path + store-and-forward, internet shortcut (GATEWAY_RELAY), beacons (DRIVER_BEACON, PRESENCE); GATE 2 | U | 1b | doing | flooding/dedup/suppression/rate-limit, discovery w/ disjoint backup, cost fn (spec weights), source router (1+3 retries 100/200/400, failover chain), store-and-forward, oracle = 61 mesh tests green incl. 200-graph parity; beacons + GATEWAY_RELAY shortcut + GATE 2 close-out next |
| X-03 | P0 | Stage 3 simulator + 15 mandatory scenarios w/ thresholds, deterministic seeds, metrics, Dijkstra oracle, baseline.json regression gate; GATE 3 | U | 1b | todo | |
| X-04 | P0 | Stage 4 Android: BleMeshTransport (+ optional Nearby), foreground service, permissions 8-15, Keystore persistence, consent/settings, diagnostics, offline rider UX; sim bridge on emulators; GATE 4 | UI,emu | 10 | todo | |
| X-05 | P0 | Stage 5 gateway + backend + driver blindness (byte-identical driver DTOs, timing normalization, RBAC denial, contract+diff+timing tests); admin mesh map + replay + analytics; GATE 5 | I,C,UI,E2E | 10 | todo | |
| X-06 | P0 | Stage 6 physical validation on 3+ devices OR explicit "simulation/emulator-verified only" + unverified list; GATE 6 | phys | 10 | todo | |
| X-07 | P0 | Stage 7 hardening: battery budgets, chaos, v1/v2 coexistence, threat-model update, MESH_PROTOCOL/MESH_LIMITS/MESH_RUNBOOK docs; GATE 7 | U,phys | 10 | todo | |

## Security / privacy / i18n (cross-cutting)

| ID | Pri | Feature | Tests | Phase | Status | Evidence |
|----|-----|---------|-------|-------|--------|----------|
| S-01 | P0 | JWT access + rotating refresh, argon2id, rate limits, account lockout | U,I | 1 | doing | JWT/argon2id/rate limits + OTP attempt lockout done (B-02); password brute-force lockout + Postgres-backed stores land with P1-3 |
| S-02 | P0 | Immutable audit log + tamper-evidence verification tooling | U,I | 1 | todo | |
| S-03 | P0 | Encrypted document storage; PII minimization in logs/DTOs | U,I | 5 | todo | |
| S-04 | P0 | GDPR export/delete (account-level) with legal-hold support | I | 9 | todo | |
| S-05 | P0 | i18n day one: EN/FR/AR full RTL, currency/units/fee formulas configurable | U,UI,S | 2 | todo | |
| S-06 | P1 | Consent-based audio recording (legal), in-app WebRTC calls via coturn | I,UI | 11 | todo | |
| S-07 | P1 | Trusted-contact auto-share, status/announcement banners | I,UI | 9 | todo | |
| S-08 | P2 | Hourly rental, intercity, airport queues/geofenced zones, optional "propose your fare" mode, pool, courier/delivery | I | 11 | todo | |

## Notes
- P0/P1 must be complete before final phase (A9.1). P2 done or explicitly deferred with reasons.
- Every row gets evidence links (CI run, report gallery, screenshots) as it lands; features without verification stay `todo`.

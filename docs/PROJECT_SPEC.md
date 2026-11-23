# PART B: MASTER SPEC

> Saved from the master autonomous build prompt on 2026-11-23. Authoritative source: /home/z/my-project/upload/Pasted Content_1795430967449.txt. Precedence per prompt header: A overrides communication rules; C wins over B on mesh details; D wins on automation; E wins on reporting/visibility.

# PART B: MASTER SPEC
################################################################################

# ROLE
You are a senior, autonomous full-stack engineer. Design, build, test, and verify a production-grade ride-hailing platform end to end: a Kotlin Android app (Rider + Driver modes), an Admin web dashboard, and a backend. Work incrementally. Never start the next feature until the current one passes the Verification Protocol. No stubbed, mocked, or faked features in the final product.

# PRODUCT SUMMARY
A Lyft-class ride-hailing platform with:
- A Google-Maps-style main screen (full-screen map, floating search bar, bottom sheet, live nearby drivers).
- 100% free/open-source services only (no paid APIs, no credit-card-required tiers).
- An offline-first rider experience: maps and visited places without internet, local price estimates, and Bluetooth multi-hop mesh booking through opted-in drivers and riders. Max hop count is admin-configurable.
- Drivers are always connected while on duty and act as the bridge between offline riders and the server.
- A powerful Admin dashboard with control over everything and full visibility of everything, including offline activity.

# FEATURE DISCOVERY MANDATE (SCOPE IS OPEN-ENDED)
The lists in this prompt are a MINIMUM. Before coding:
1. Competitive audit of Lyft, Uber, Bolt, inDrive, Careem, Yassir, Google Maps, Waze (screens, settings, flows, safety, driver tools, admin tools) into /docs/COMPETITIVE_AUDIT.md.
2. Add every good, standard, or differentiating feature found, with priority (P0 core, P1 expected, P2 nice-to-have) in FEATURES.md. Build P0 and P1 fully.
3. Think through each role's full lifecycle (install -> daily use -> problems -> leaving) so every screen, setting, empty state, error state, and edge flow exists.
Suggested additions (implement what makes sense; justify skips in DECISIONS.md): ride-for-someone-else, favorite/blocked drivers, split fare, women-rider/driver preference (admin-enabled), accessibility vehicles, child seat, pet-friendly, pool, courier/delivery, hourly rental, intercity, airport queues and geofenced zones, optional "propose your fare" mode (admin toggle), in-app voice/video via self-hosted WebRTC (coturn), consent-based audio recording, route-deviation and long-stop alerts, trusted-contact auto-share, crash detection, driver fatigue limits, driver quests/incentives/tiers, rider loyalty tiers, passes/subscriptions, corporate accounts, gift vouchers, tax invoices, insurance claims, lost-and-found, dispute center, surveys, onboarding tutorials, data-saver and low-end device modes, widget/quick tile, app shortcuts and deep links, GPS-spoofing and fraud detection, device binding, referral programs, status/announcement banners, notification preference center.

# HARD CONSTRAINTS
1. Android: Kotlin, Jetpack Compose, Material 3, MVVM + Clean Architecture, Hilt, Coroutines/Flow, Room (+FTS), DataStore, WorkManager, Retrofit/Ktor client, min SDK 26, target latest stable SDK. Offline-first with an outbox pattern for every rider/driver write.
2. Maps: MapLibre Native Android with self-hosted vector tiles (OpenMapTiles/Planetiler -> PMTiles/MBTiles via tile server). Do NOT bulk-download from or depend on tile.openstreetmap.org. Routing: self-hosted OSRM or Valhalla. Geocoding: self-hosted Nominatim/Photon. No Google Maps SDK, paid Mapbox, or Google Places. Tile URL/style configurable. Respect ODbL attribution.
3. Realtime/push: self-hosted WebSocket gateway + optional UnifiedPush/ntfy. FCM only as an optional adapter. No paid service mandatory.
4. OTP/auth: pluggable `OtpProvider` with adapters: Telegram bot, email/SMTP, self-hosted SMS gateway, optional WhatsApp (document: needs the official Cloud API allowance or a self-hosted bridge, with ToS risk). Also email+password and phone+password. Admin enables/disables each method and sets priority. OTPs: 6 digits, hashed, 5-min expiry, rate- and attempt-limited.
5. Backend: Kotlin (Ktor) or Spring Boot, PostgreSQL + PostGIS, Redis, Docker Compose for the whole stack, one-command local run.
6. Admin: React + TypeScript + Vite, same REST API, server-side RBAC.
7. Payments: internal double-entry wallet/points ledger, cash, and a `PaymentProvider` interface for future gateways. Points transfers are first-class.
8. Security: JWT access + rotating refresh, argon2/bcrypt, server validation, immutable audit log, encrypted document storage, PII minimization, GDPR-style export/delete.
9. i18n from day one: English, French, Arabic (full RTL). Currency, units, fee formulas configurable.

# ARCHITECTURE DELIVERABLES (before code)
ARCHITECTURE.md (Mermaid diagrams, ER diagram, OpenAPI spec, WebSocket event catalog, state machines for trip, driver duty, document verification, mesh message lifecycle, offline outbox), FEATURES.md (checkbox, priority, owner module, tests, evidence links), DECISIONS.md, THREAT_MODEL.md, COMPETITIVE_AUDIT.md.
Repo layout: /android, /backend, /admin-web, /mesh-core (pure Kotlin/JVM, no Android deps), /e2e, /tools/test-viewer, /infra, /docs, /scripts, /.github.

# ROLES AND FEATURES

## Rider
- Onboarding, signup/login (admin-enabled methods), profile, photo, emergency contacts, saved places, language, dark mode, accessibility settings, privacy center (data export/delete, location-history controls).
- Main screen: full-screen map, current location, search bar, recent/saved/visited places, bottom sheet of ride types, live nearby drivers as smoothly moving markers.
- Tap a driver marker: name, photo, rating, vehicle model/color/plate, ride type, ETA, price estimate, trips completed, languages, "Request this driver".
- Book now: pickup/dropoff, multi-stop, route preview, admin-configurable ride types, price estimate (with disclaimer), promo code, payment method (cash/wallet/points), notes. Book a specific driver or auto-dispatch (admin-configurable strategy, timeout, radius expansion).
- Schedule for later (reminders, dispatch window, edit/cancel). Concurrent trips (admin-configurable limit) with a clear multi-trip UI.
- Live trip: driver tracking, ETA, in-app chat, call masking/VoIP, trip sharing link, SOS, safety check-ins, pickup PIN, route-deviation alerts.
- Cancel (reasons, admin fee rules, free-cancel window), rating, tip, report issue, lost item, PDF receipt, filterable history, rebook.
- Wallet: balance, top-up (vouchers/cash agents/admin credit), send points/money by phone/username/QR, request money, history, limits/fees from admin config, PIN confirmation.
- Referrals, promotions, notification center, support tickets/chat, FAQ, legal.
- Offline Network settings and diagnostics (see Mesh).

## Driver
- Multi-step onboarding: personal info, vehicle(s), documents (ID, license, registration, insurance, selfie, vehicle photos) with expiry dates, status tracker with rejection reasons, re-upload flow.
- Duty toggle, heading-home, destination filter, demand heatmap, surge zones, quests/incentives, tiers.
- Incoming request with countdown, accept/decline, in-app turn-by-turn (OSRM instructions + Android TTS), pickup PIN, start/stop, waypoints, wait-time charge, cancel reasons.
- Earnings (daily/weekly, per-trip breakdown, commission, cash vs wallet, payouts), ratings, acceptance rate, document-expiry alerts, vehicle management, history, support, notifications.
- Settings: "Accept offline riders" opt-in and "Relay for other riders" opt-in. The driver can never see which riders are offline.

## Admin (RBAC: Super Admin, Operations, Support, Finance, Verifier, Read-only, custom roles)
- Live map: online riders, drivers by status, active trips, OFFLINE riders (last known position, hops, gateway, path), mesh nodes/links, filters, click for details, force-offline, message.
- User management: create/edit/delete/search riders, drivers, admins; CSV import/export; read-only impersonation (audited); reset password; force logout; sessions/devices.
- Verification center: document queue with zoom/rotate viewer, per-document approve/reject with reason, verify/unverify, expiry tracking, re-verification triggers.
- Bans/suspensions (temporary/permanent, device/phone blocklist, appeals).
- Messaging: targeted push/in-app/OTP-channel messages by role/city/segment/user, templates, scheduling, delivery stats.
- Trips: live and historical, full timeline, route replay, edit/cancel/refund, disputes, manual assignment, fraud flags, and an admin-only "Origin: online / offline-mesh" field with the full mesh path.
- Money: fares per city and ride type (base, per km, per minute, minimum, booking fee, cancel fee, wait fee, night/peak, surge), commissions, taxes, promos, ledger viewer, manual credit/debit (reason required), payout approvals, points-transfer rules/limits/fees, reports, reconciliation, exports.
- Config center (hot-reloadable, versioned, rollback, signed bundles for devices): login methods, OTP providers, mesh settings (max hops, TTL, per-region enable, beacon interval, relay limits, route-score weights, quote validity), offline cache limits, matching rules, scheduling window, concurrent-trip limit, cancellation rules, ride types, service-area polygon editor, feature flags, min app version/force update, maintenance mode, legal texts, branding.
- Analytics: DAU/MAU, funnels, heatmaps, cancellations, driver performance, revenue, mesh stats (relayed messages, average hops, success rate, delivery latency, path-failure rate, offline-originated trips).
- Support inbox with SLA timers, security center (audit log, TOTP 2FA, IP allowlist, sessions, rate limits, fraud signals), system health, GDPR requests, lost-and-found, safety incident workflow.

# OFFLINE MAPS AND VISITED PLACES
- Every place the user views, searches, picks, travels to or from, or dwells at (opt-out in settings) is saved as a `VisitedPlace` in Room (id, name, address, lat/lng, category, firstVisited, lastVisited, visitCount, source, favorite, note, tileRegionId). Encrypted at rest. Optional E2E-encrypted account sync.
- Automatic tile caching around each place (configurable radius and zoom range, e.g. 2 km, z10-z17), a low-zoom base pack for the home city, and corridor caching along completed routes. LRU eviction, global size cap (user and admin configurable), optional Wi-Fi-only. Use MapLibre offline regions or a custom cache; document the choice.
- Offline Map screen: regions, storage usage, pin-forever, delete, clear all, manual city download.
- Offline search over visited/saved places (Room FTS), offline reverse-geocoding for visited places, cached recent routes viewable offline.
- Map layers: visited places (clustered/heat), saved places, nearby mesh drivers (when offline), toggled from a layers menu.
- Verify with airplane mode via adb: cached areas render with tiles, search and markers work, uncached areas show a graceful placeholder. Screenshots as evidence.

# PRICING: ESTIMATE VS FINAL QUOTE
- Estimate (online and offline): shown as a range with the permanent note "Estimated price. This is not the final price." Online, from the backend using OSRM distance/time plus fare config. Offline, computed on-device from a signed, versioned fare-config bundle cached while online (expiry and "stale config" flag), using the best available distance: cached route, else offline routing for cached regions if feasible, else straight-line x calibrated detour factor. Show ride-type estimates, surge indication when known, fees breakdown.
- Final quote (authoritative): always computed by the server, signed, with an admin-configurable validity window and policy (upfront-locked or metered with cap). For an offline rider the quote travels back through the driver/gateway and the mesh path. The rider sees the final price, the difference from the estimate, and confirms or declines. Confirmation travels back the same way. Wallet holds happen server-side at confirmation.
- Unit-test the fare calculator extensively, including server vs on-device parity within a stated tolerance.

# OFFLINE BLUETOOTH MESH (SUMMARY; FULL SPEC IN PART C)
Integration points, designed from Phase 0:
- Opt-in only for riders and drivers, with a consent screen (device may relay encrypted messages for others; offline riders' approximate location is visible to the platform operator for safety/dispatch), foreground service, Android 12+ Bluetooth permissions.
- Drivers must be online while on duty and act as gateways. On connection loss: banner, "degraded" in admin, and after an admin-configurable grace period the driver goes off-duty and stops beaconing. Drivers with "Accept offline riders" enabled continuously advertise beacons. Riders with internet may also be gateways.
- `MeshTransport` interface with two implementations: BLE/Nearby Connections, and `SimulatedMeshTransport` for emulators/tests. The mesh core is pure Kotlin.
- Offline booking flow: local estimate (not final) -> request via BLE/hops -> gateway forwards to backend -> server creates trip and computes signed final quote -> quote returns by best-scored path -> rider confirms -> trip proceeds; outbox syncs when connectivity returns.
- Driver blindness: driver-facing DTOs, WebSocket events, notifications (text, sound, timing), UI, local DB, logs identical for online and offline-originated requests, with no origin field. Origin, hop count, and mesh path live only in admin-only tables with RBAC. Contract tests and screenshot comparison mandatory. Residual risks (rooted device, BLE sniffing, timing side channels) go in THREAT_MODEL.md.
- Admin visibility: offline riders (via relayed PRESENCE), nodes and links, animated request/quote paths, partitions, time-slider replay, mesh analytics.
- Mesh message metadata and paths go into the tamper-evident event store.
- Be honest in all docs about BLE range (~10-100 m), background limits, OEM battery killers.

# FULL HISTORY AND AUDITABILITY
Append-only, tamper-evident (hash-chained) event store for: trips and state changes, location pings (compressed, partitioned), mesh message metadata and paths, quotes, wallet entries, config versions, admin actions, document reviews, logins, bans. Configurable retention, search, filters, export, GDPR-compliant deletion/anonymization with legal-hold support. Admin can reconstruct any trip end to end, including offline-originated ones.

# DELIVERY PLAN (each phase ends with a verified, demoable build AND a green CI pipeline)
0. Architecture, specs, competitive audit, repo, CI skeleton (Part D), Test Viewer + report gallery (Part E), docker-compose (tile server, OSRM, Nominatim), seed data.
1. Backend core: auth, users, roles, config service (signed bundles), audit/event store. Admin shell + login.
   1b. (Parallel track) Mesh core: pure Kotlin protocol, routing, path scoring, SimulatedMeshTransport, tested in CI per Part C Stages 1-3.
2. Android shell: theming, i18n, navigation, auth, offline-first foundations, MapLibre main screen.
3. Offline maps and visited places.
4. Rider booking happy path (including on-device price estimator).
5. Driver app flow.
6. Admin: verification center, users, live map, trips, fares.
7. Wallet/ledger, points transfer, promos, payouts, fees.
8. Scheduled and concurrent trips, cancellations, ratings, chat, SOS, support, discovered P0/P1 extras.
9. Notifications, messaging, bans, analytics, security center, history viewer.
10. Mesh integration (Part C Stages 4-7): BLE/Nearby on real devices, gateway flow, server quote relay, driver-blindness tests, admin mesh map, physical-device test plan.
11. Hardening: performance, accessibility, security review, load test, docs, release build.

# VERIFICATION PROTOCOL (DEFINITION OF DONE, PER FEATURE)
1. Unit tests (domain, ViewModels, repositories, fee calculators, routing/path scoring, cache eviction).
2. Backend integration tests (API contract, auth/RBAC, DB constraints, ledger invariant debits = credits, driver-DTO parity).
3. UI tests (Compose/Espresso/UiAutomator) for the main flow and key error states, written with the Step Storyboard helper (Part E).
4. Emulator run with two emulators (rider + driver) plus the admin dashboard in a browser, verifying cross-actor flows in real time.
5. adb verification: clean `logcat` (no crashes/ANRs/StrictMode violations), `dumpsys` permissions/services/battery, `adb emu geo fix` for movement, airplane-mode and network throttling, `am` for deep links and process death/restore.
6. Visual verification: screenshots on 2+ screen sizes, dark mode, RTL Arabic, large font scale. LOOK at them yourself, fix layout bugs, recapture. Store under /docs/evidence/<feature>/.
7. Edge cases: no/slow network, permission denied, GPS off, app killed mid-trip, duplicate taps, two drivers accepting the same trip, clock skew, invalid input, expired token, banned user mid-session, stale fare config, expired quote, full cache storage.
8. Cross-layer check: UI state -> ViewModel -> repository -> payload -> backend handler -> DB rows -> WebSocket events -> admin view, including DB rows and audit/event log.
9. Regression: full suite + smoke e2e, and the GitHub Actions pipeline green on the pushed commit.
10. Update docs and FEATURES.md with evidence (report links, screenshots); commit; then next feature.
If any step fails, fix and repeat.

# QUALITY BAR
Professional UX (design system, motion, skeleton/empty/loading/error states, TalkBack labels, contrast, touch targets, smooth 60fps map). ktlint/detekt, static analysis, no secrets in repo, env-based config, KDoc on public APIs, structured logs, optional self-hosted Sentry/GlitchTip and Prometheus/Grafana. Docs: one-command README, admin/rider/driver guides, API docs, mesh protocol spec, threat model, runbook.

################################################################################

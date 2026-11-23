# DECISIONS - ride-hailing-platform

Append-only log of non-obvious choices and fallbacks. Newest sections at the bottom.

## 2026-11-23 - Phase 0

- **D1: Repo created before pushing workflows.** `gh repo create` (empty) ran first, then settings/Pages were enabled, then the scaffold was pushed. This avoids a race where the first `deploy-pages` job runs before Pages is provisioned. End state is identical to `gh repo create --source=. --push`.
- **D2: Branch protection requires `ci-summary` but `enforce_admins=false`.** CI gates PRs; the owner can still push operational commits (STATE/DECISIONS updates) without a bypass ceremony. Revisit if other contributors join.
- **D3: No required PR reviews.** Solo development; requiring reviews would block merges. PR template and CODEOWNERS carry the review convention instead.
- **D4: Keystore shape and backup design.** PKCS12 (modern default Android accepts), RSA-4096, 10,000-day validity, alias `ride-hailing-release`, DN derived from the repo name. Passwords exist only as Actions secrets and inside the encrypted backup archive. The backup passphrase is stored in a file beside the archive by design, so recovery never depends on chat or logs; tradeoff: anyone with both files can sign releases - move both into a password manager and delete local copies.
- **D5: Cost control shape.** PR CI is intentionally light (no emulator). `nightly.yml` at 02:30 UTC is the extension point for the emulator matrix and integration tests once the Android/backend code lands. If the repo is ever made private: a full run is roughly 10-20 runner-minutes and an emulator matrix nightly could reach 1,500-3,000 min/month, over the 2,000 min/month free quota - keep PR runs small and the full matrix nightly only. Repo is currently public, so minutes are free and unlimited.
- **D6: CI jobs self-skip until code exists.** Android/Node/Docker jobs detect their project and skip with a notice on an empty scaffold, so the required `ci-summary` check is meaningful from day one and the first run is green.
- **D7: LICENSE is MIT with the repo owner as copyright holder** - placeholder pending the owner's preferred license.
- **D8: Commits use the owner's GitHub noreply email** (`65675644+stev199@users.noreply.github.com`); no personal email exposed in history.
- **D9: Token handling.** Personal token is used only via the local `GH_TOKEN` environment variable (never written to disk, `.git/config`, repo files, or workflow YAML). Git push uses a repo-local `credential.helper` that delegates to `gh`. Workflows rely on the built-in `GITHUB_TOKEN` with explicit least-privilege `permissions:`; future secret-using jobs must be guarded against fork PRs (pattern documented at the bottom of ci.yml).
- **D10: Retry policy applied.** Failures are diagnosed from failed-job logs, fixed in the generator script, and re-run (max 3 attempts) before being escalated to BLOCKERS.md.
- **D11: Secret scanning / push protection API returned 404** (token lacks the `security_events` scope; endpoint hidden). Fallback: as a public repository, secret scanning and push protection are enabled by default - confirmed via `GET /repos/...` -> `security_and_analysis`: `secret_scanning: enabled`, `secret_scanning_push_protection: enabled`. No gap remains; a future token with `security_events` could manage these via API if ever needed.
- **D12: `gh api -f` sends string values;** boolean settings must use `-F` (typed). Initial Actions-permissions PUT failed with 422 for this reason and was corrected.
- **D13: gh CLI is not preinstalled and apt is unavailable** in the sandbox; installed from the official `cli/cli` release tarball to `/home/z/my-project/tools/gh` (outside the repo) and put on PATH per session.

## 2026-11-23 - Phase 0 continuation (build kickoff)

- **D14: Degraded local environment.** Sandbox has no KVM, no Docker daemon, 2 vCPU, ~8GB disk: local emulator and compose verification are delegated to CI (D4/D5 jobs on GitHub runners which have KVM). Local JDK17 + Gradle 8.10.2 installed for JVM work. Android SDK locally is deferred to Phase 2 start (BACKLOG P0-8, revisited if environment changes).
- **D15: D11.9 (first-green pipeline with trivial tests on two emulators) is sequenced after Phase 1/1b skeletons exist** - it requires backend/admin/mesh code to test. The CI skeleton ships now with every D3 job wired and self-skipping; item tracked as BACKLOG P0-C so it cannot be forgotten before Phase 1 completes.
- **D16: `hashFiles()` is step-scoped only (actionlint-enforced).** All module-presence guards use a first "guard step" pattern writing GITHUB_OUTPUT; actionlint (pinned v1.7.7) runs in lint-and-static on every commit.
- **D17: OpenAPI lint** = spectral with `spectral:oas` ruleset at repo root + docs/api/.spectral.yaml; 0 errors required, warnings tolerated until Phase 1 tightens it.
- **D18: Background processes do not survive tool calls in this sandbox** - long downloads run synchronously (JDK17 ~1min, Gradle ~1min observed).
- **D19: Compose profiles** `infra` / `geo` / `app` / `test` keep the geo stack heavy services separate from per-shard test stacks; no tile.openstreetmap.org anywhere (Planetiler on a small geofabrik extract; ODbL attribution noted).
- **D20: Competitive audit is knowledge-based desktop research** (no scraping/paid tools), recorded as directional in docs/COMPETITIVE_AUDIT.md; adoption table drives FEATURES.md.
- **D21: gitleaks/actionlint pinned releases** used identically locally (scripts/ci-local.sh) and in CI to avoid drift.
- **D22: report-publish supersedes pages-report.yml**; Pages keeps last-run layout /runs/<run-id>/ and root = latest; artifact test-report-<run-id> is the fallback channel (E6.2).

## 2026-11-23 - CI green bring-up (Phase 0 close-out)

- **D23: CI fix trail (retry policy applied, all fixed within budget).** (1) `hashFiles()` is step-scoped only -> guard-step pattern; actionlint now also runs locally in scripts/ci-local.sh. (2) trivy-action tags are v-prefixed now -> `@v0.36.0` (validated against the live tag list). (3) dependency-review SPDX id `GPL-2.0-only-with-classpath-exception` rejected -> dropped. (4) CI runs shellcheck (sandbox does not) -> shellcheck 0.10.0 installed locally; workflows SC2016/SC2034/SC2035/SC2086 fixed. (5) gitleaks `generic-api-key` false positive on actions/cache `key:` -> `.gitleaksignore` fingerprint; **gitleaks fingerprints reference the introducing commit, so checkouts scanning history must use `fetch-depth: 0`** (this was the last red).
- **D24: Required check is `ci-summary` and it behaved correctly** during the failures (failed the run, listed failing jobs) - branch protection + admin bypass verified end-to-end.
- **D25: Report gallery publication verified live** (E6.1 mode): `/latest` + `/runs/<run-id>/` on Pages with artifact fallback uploaded.

## 2026-11-23 - Phase 1 skeleton + mesh Stage 1

- **D26: OpenAPI tracks the implemented surface only** (contract truth). Auth paths return with P1-2; documented 404 Error shape covers all unknown routes via StatusPages.
- **D27: Maven Central 429-rate-limits the sandbox IP** -> local-only Gradle init script (tools/gradle-home/init.d) swaps in Google's central mirror via GRADLE_USER_HOME; CI resolution unchanged.
- **D28: Reassembler/seen-cache keys must be content-hex strings** - ByteArray equals/hashCode are identity-based in JVM (caused silent reassembly failure; fixed and unit-tested).
- **D29: ci.yml gains workflow_dispatch as a forced full run** - component jobs run regardless of path filters; used to verify compose+contract stage without synthetic commits.
- **D30 (T-2 resolved): mesh-core uses JDK 17 built-ins** (Ed25519, X25519, ChaCha20-Poly1305, HKDF via HmacSHA256) - no Tink/libsodium dependency; revisit only if Android API level demands it.
- **D31: session cwd resets between tool calls; a bare git commit ran twice in the sandbox session repo (/home/z/my-project)** - both reset (mixed, nothing lost, never pushed); all repo operations now use explicit git -C and absolute paths.
- **D32: mesh envelope signature covers immutable header + payload (NOT the signature field)**; immutableRegion() excludes 64B sig + hop section - pinned by exhaustive bit-flip test over every header bit.
- **D33: Flyway community = up-migrations only**; schema changes use a forward-fix policy; "migrations up tested in CI" is satisfied, down-migrations are documented as out of scope for the community edition (deviation from D3.2, accepted).

## 2026-11-23 - P1-2 auth + P1B Stage 2 routing

- **D34: JWT stack = com.auth0 java-jwt HS256** (single implementation for issuance and verification; typ=access + role claims; secret from env `AUTH_JWT_SECRET` >= 32 bytes). Dev/test fall back to a per-process random secret, which invalidates tokens across restarts - acceptable for dev, production MUST inject the real secret.
- **D35: Refresh-token reuse detection revokes the whole family** (stolen-token response). Bounded-memory trade-off: consumed generations are pruned except the immediately-previous one, so reuse of the latest rotated token (the realistic theft window) is always detected; full-family retention moves to the Postgres-backed store with P1-3.
- **D36: OTP codes are stored as HMAC-SHA256(channel|requestId|code)** with a server key (`OTP_HASH_KEY` env or per-process random in dev); the requestId is the per-entry salt. 6 digits = 1M keyspace - the defences are the 5-attempt lockout, 45 s resend window and 3 sends/10 min per identifier, all unit-tested.
- **D37: OTP adapter set for P1-2**: DevInbox (dev/test default; `devOnly` flag refuses production `APP_ENV`), Telegram Bot API, self-hosted HTTP SMS gateway, SMTP email (STARTTLS enforced). Remote adapters are wired only when their env config exists; `RemoteTransport` is an injected seam so adapter request-building is unit-tested without network. WhatsApp adapter deferred per spec ToS-risk note (B-04); email OTP request payload contract-checked.
- **D38: Per-origin mesh rate limits apply to DISTINCT messages only.** Duplicate receptions (normal multi-path mesh behavior) are governed by counter-based suppression (k=2); rate-limiting them would misclassify legit flooding as spam and break GATE 3 scenario 3/12 semantics.
- **D39: "3 tries, backoff 100/200/400 ms" (spec ambiguity) = initial attempt + 3 retries (4 attempts)**, consuming the three delays between attempts. Recorded so simulators and transports agree.
- **D40: The gateway-staleness penalty (w_gw) applies only to gateway-bound routes, once per path.** Non-gateway paths exclude it, which keeps per-hop costs additive and makes the Dijkstra oracle parity exact (GATE 3 scenario 7).
- **D41: Rebroadcast jitter interpretation**: uniform 20-200 ms scaled by +5% per neighbor, capped at 200 ms / floored at 20 ms (spec: "random 20-200 ms scaled by neighbor count").
- **D42: The 4-byte breadcrumb hop id = FNV-1a 32-bit hash of the logical NodeAddress** (spec's "hashed hop ID"); stable and unit-testable, upgraded to the wire profile hash when beacons land.
- **D43: Gradle mirror init script recreated at scripts/gradle-init.gradle after the session recycle wiped GRADLE_USER_HOME** (extends D27: Maven Central 429s on the shared sandbox IP; aliyun public/gradle-plugin mirrors local-only, CI unchanged; cache now persists in tools/gradle-home).
- **D44: Password policy = documented length rule only at the server.** Schemathesis ST202 caught the trivial/all-digit rejection answering 400 to schema-valid passwords; server policy now enforces exactly what openapi.yaml states (minLength 10); common-password rejection belongs to API consumers and must be schema-synced if server-side (planned P1-3).
- **D45: Malformed request bodies -> 400 invalid_body (BadRequestException handler in StatusPages).** Schemathesis ST101/ST103: Ktor body-conversion failures were surfacing as 500s on /v1/auth/*; 400 is now documented on every POST auth endpoint.

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

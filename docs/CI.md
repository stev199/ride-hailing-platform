# CI.md

CI/CD reference (Part D authoritative spec: /docs/CI_SPEC.md). Track pipeline health here.

## Workflows

| Workflow | Trigger | Jobs | Notes |
|---|---|---|---|
| ci.yml | push main + PRs | changes -> lint-and-static, backend-test -> api-contract, admin-web-test, mesh-core-sim, android-build -> ui-rider/ui-driver/e2e-cross-actor, security -> report-publish -> ci-summary | path-filtered; component jobs self-skip until their code lands; `ci-summary` is the only required check |
| nightly.yml | schedule 02:30 UTC + dispatch | extended device/API/locale matrix, long mesh sim, full security scans, AVD snapshot warm | fail-fast false |
| release.yml | tag v* | backend/admin images to GHCR, signed APK/AAB, SBOM, Trivy, GitHub Release + changelog | refuses to publish if migration tests fail |
| codeql.yml | push/PR/weekly | CodeQL Kotlin+JS | only when source exists |
| dependency-review.yml | PRs | dependency review (license/vuln gate) | |
| live-view.yml | dispatch (opt-in) | stack + 2 emulators + viewer + cloudflared tunnel w/ random token | never on fork PRs; auto-teardown |
| avd-cache.yml | android/** changes + dispatch | create+boot AVD, save snapshot to actions/cache | keeps emulator jobs fast |

## Branch protection

- `main` requires status check `ci-summary` (only requirement). Linear history enforced; force-push/deletion blocked; admins may bypass for operational doc commits (DECISIONS D2).
- Concurrency: `ci-${{ github.ref }}` with cancel-in-progress.

## Definition of Done (CI additions, D10)

Feature is done only when: tests wired into the right CI jobs; pipeline green on GitHub (or "CI not run on GitHub" + reason); new UI has storyboard + RTL/dark screenshot tests; new APIs in OpenAPI with contract/RBAC/negative tests; new admin screens have Playwright + axe; ci-summary shows no new flaky tests / no coverage drop / no new security findings above agreed severity; report gallery shows the feature's screenshots/videos.

## Flaky policy

Retries: max 2 per failed test; every retried test is reported as `flaky` and listed in /docs/FLAKY.md; a test flaky in 3 consecutive runs must be fixed or quarantined with a BACKLOG entry. Never delete or loosen a test to get green.

## Artifacts (retention 14d, nightly 30d)

JUnit XML, HTML reports, JaCoCo, Playwright traces/videos, emulator screenshots/videos/logcat, backend+compose logs, mesh metrics/charts, APK/AAB, SBOM. Curated evidence snapshots from main go to /docs/evidence/ci/.

## Cost tracking

Repo is public -> Actions minutes free/unlimited. Record per-run durations here as the pipeline grows; keep PR feedback <20 min target; full matrix stays in nightly.

| Date | Run | Duration | Note |
|---|---|---|---|
| 2026-11-23 | CI run 45159002876 | ~1m | skeleton (all jobs skip) |
| 2026-11-23 | CI run 45159335073 | ~1m | skeleton + docs commit |

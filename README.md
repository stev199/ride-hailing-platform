# ride-hailing-platform

Monorepo for a ride-hailing platform: Android apps, Node.js backend, Docker infrastructure - with CI, a nightly full-matrix run, and an automated report gallery on GitHub Pages.

## Planned layout

| Path | Purpose |
|------|---------|
| `android/` | Android application (Gradle) - lands in a later phase |
| `backend/` | Kotlin (Ktor) backend: API, WebSocket gateway, mesh-gateway - Phase 1+ |
| `docs/` | Project documentation; `docs/reports/` feeds the Pages gallery |
| `.github/workflows/` | CI, nightly, and Pages deployment workflows |

## Workflows

- **CI** (`ci.yml`) - every push to `main` and every PR. Path-filtered job graph (D3): lint-and-static, backend-test -> api-contract, admin-web-test, mesh-core-sim, android-build -> rider/driver UI shards + cross-actor E2E, security, then `report-publish` (gallery to Pages) and the required `ci-summary` check. Component jobs self-skip until their code lands.
- **Nightly** (`nightly.yml`) - full device/API/locale/theme matrix, long mesh simulation, full-history secret scan.
- **Release** (`release.yml`) - on tags `v*`: GHCR images, signed APK/AAB, SBOM, changelog release.
- **CodeQL / Dependency Review** - scheduled security analysis; PR dependency gate.
- **Live View** (`live-view.yml`, opt-in dispatch) - stack + emulators + Test Viewer behind a token-protected quick tunnel.
- **AVD cache** (`avd-cache.yml`) - emulator snapshot warm-up for fast UI shards.

## Quickstart

```bash
make up          # infra + geo stacks (Docker required; CI and dev machines)
make viewer      # Test Viewer (live/replay evidence), http://localhost:7070
make report      # build report gallery from /artifacts
make ci-local    # local pipeline subset (actionlint, gitleaks, spectral)
./infra/fetch-map-data.sh   # small city extract -> tiles + OSRM (needs Docker)
```

Data licensing: map/routing data is OpenStreetMap-derived (ODbL attribution required wherever tiles/routing are displayed).

## Governance

- Branch protection on `main` requires the `ci-summary` check (admins may bypass - see DECISIONS.md).
- Labels: `bug`, `feature`, `flaky`, `blocked`, `phase-1` ... `phase-11`.
- Issue and PR templates plus CODEOWNERS live under `.github/`.
- Release signing material exists only as Actions secrets (`KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`); an encrypted backup is kept outside the repo - location recorded in STATE.md. Never commit keystores.
- Security: Dependabot alerts and security updates, secret scanning and push protection are enabled; default workflow token is read-only.

## Status

See [STATE.md](STATE.md) for phase-by-phase results and [DECISIONS.md](DECISIONS.md) for the decision log.

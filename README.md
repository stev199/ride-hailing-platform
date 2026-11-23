# ride-hailing-platform

Monorepo for a ride-hailing platform: Android apps, Node.js backend, Docker infrastructure - with CI, a nightly full-matrix run, and an automated report gallery on GitHub Pages.

## Planned layout

| Path | Purpose |
|------|---------|
| `android/` | Android application (Gradle) - lands in a later phase |
| `backend/` | Node.js backend service - lands in a later phase |
| `docs/` | Project documentation; `docs/reports/` feeds the Pages gallery |
| `.github/workflows/` | CI, nightly, and Pages deployment workflows |

## Workflows

- **CI** (`ci.yml`) - every push to `main` and every PR. Android, Node, and Docker jobs self-skip until their code exists, then a single `ci-summary` job aggregates the result. `ci-summary` is the only branch-protection requirement.
- **Nightly** (`nightly.yml`) - scheduled full run; reserved for the emulator matrix once the Android project exists, keeping PR runs cheap.
- **Pages Report** (`pages-report.yml`) - rebuilds and deploys the report gallery on every push to `main`.

## Governance

- Branch protection on `main` requires the `ci-summary` check (admins may bypass - see DECISIONS.md).
- Labels: `bug`, `feature`, `flaky`, `blocked`, `phase-1` ... `phase-11`.
- Issue and PR templates plus CODEOWNERS live under `.github/`.
- Release signing material exists only as Actions secrets (`KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`); an encrypted backup is kept outside the repo - location recorded in STATE.md. Never commit keystores.
- Security: Dependabot alerts and security updates, secret scanning and push protection are enabled; default workflow token is read-only.

## Status

See [STATE.md](STATE.md) for phase-by-phase results and [DECISIONS.md](DECISIONS.md) for the decision log.

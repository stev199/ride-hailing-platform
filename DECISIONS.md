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

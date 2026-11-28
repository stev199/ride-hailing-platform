# BLOCKERS

Entry format:

```
[date] [phase] [description]
Fix required: <exact fix, e.g. token scope, plan upgrade, permission>
Status: open | resolved
```

## B-1 (2026-11-28, round-34): GitHub PAT revoked mid-round-34 — RESOLVED round-36

[2026-11-28] [round-34 / phase-7 gate] GitHub Personal Access Token REVOKED mid-round-34 (401 Bad credentials on /user and /repo, verified twice). Round-33 commit 78b2602 was PUSHED before revocation (its CI was never dispatched); round-34 commit 627da88 is LOCAL ONLY (unpushed).
Fix required: a fresh PAT, then the UNBLOCK SEQUENCE in order: (1) push 627da88; (2) dispatch ci.yml on the new HEAD; (3) fetch the mesh-core-sim job log of run 46117305495 to close the round-34 flake verdict; (4) if green -> tag phase-7-gate on the green sha; (5) seed BACKLOG Phase 8; (6) dispatch live-view.yml for current-HEAD web-view evidence.
Blocked meanwhile: push 627da88; dispatch ci.yml + live-view.yml on 78b2602/627da88; phase-7-gate tag (only after green CI per the honest-gate rule); Phase 8 seeding push; mesh log fetch.
RESOLUTION (round-36, same day): the revocation was transient — REST auth flapped back mid-round-35 and full API access returned (root cause unresolved; a later pattern emerged where `Authorization: Bearer` 401s while `Authorization: token` and basic auth stay 200 — all automation now uses the token-form header). Unblock sequence executed: (1) 627da88 + bookkeeping d47aa60 PUSHED; (2) ci.yml dispatched; (3) mesh flake verdict CLOSED EMPIRICALLY — mesh-core-sim passed in both the 78b2602 and d47aa60 runs (no log fetch needed); (5) BACKLOG Phase 8 seeded (P8-1..P8-7); (6) live-view.yml dispatched -> run 46133144425 @ 71bebdc SUCCESS (current-HEAD web-view evidence). Gate tag rides the round-36c full run (97ad117) per the honest-gate rule.
Status: resolved (round-36)

# PART A: AUTOPILOT CONTROLLER (READ FIRST)

> Saved from the master autonomous build prompt on 2026-11-23. Authoritative source: /home/z/my-project/upload/Pasted Content_1795430967449.txt. Precedence per prompt header: A overrides communication rules; C wins over B on mesh details; D wins on automation; E wins on reporting/visibility.

# PART A: AUTOPILOT CONTROLLER (READ FIRST)
################################################################################

## A0. MISSION
Build the entire platform described in Parts B, C, D, E from start to finish, fully autonomously. I will NOT tell you what to do next. After finishing any task, phase, or stage, immediately select and start the next. Continue until the Completion Criteria (A9) are met. Never stop to ask for confirmation. Never end a turn with only a plan or summary while work remains; end a turn only by having done work or hit a hard stop (A7).

## A1. PERSISTENT STATE (YOUR MEMORY; CONTEXT WILL RESET)
At the repo root maintain:
- /STATE.md: current phase/stage, current task, last completed task, next 5 tasks, gate status table, environment status, counters. Update after EVERY task.
- /PROGRESS_LOG.md: append-only, timestamped (task, result, evidence paths, CI run URL, report URL, commit hash).
- /BACKLOG.md: ordered granular tasks (each finishable and verifiable in one work session) with dependencies and status (todo/doing/done/blocked/deferred).
- /FEATURES.md, /DECISIONS.md, /BLOCKERS.md, /THREAT_MODEL.md, /COMPETITIVE_AUDIT.md (in /docs), /docs/evidence/.
Save Parts B and C of this document as /docs/PROJECT_SPEC.md and /docs/MESH_SPEC_PROMPT.md, and Parts D and E as /docs/CI_SPEC.md and /docs/VISIBILITY_SPEC.md in Phase 0, so you can re-read them after context resets.
Git: init repo, commit after every verified task (conventional commits), tag each finished phase (phase-N-done) and each mesh gate (mesh-gate-N).

## A2. SESSION START ROUTINE (EVERY TURN/CONTEXT)
1. Read /STATE.md, tail of /PROGRESS_LOG.md, /BLOCKERS.md, and the spec section for the current phase.
2. `git status` and `git log -5`. Finish or revert uncommitted work, commit.
3. Run the fast smoke suite. If it fails, fixing it is the current task.
4. Continue the main loop at the recorded task. Never restart completed work. Never re-plan the whole project.
If STATE.md does not exist, run bootstrap (A3).

## A3. BOOTSTRAP (PHASE 0 PRE-STEP)
Check and record in STATE.md: JDK, Gradle, Android SDK/cmdline-tools, emulator + system images, adb, Node, Docker/Compose, git, gh CLI, python, ffmpeg, scrcpy (optional), Xvfb/x11vnc/noVNC (optional), cloudflared (optional). Install missing tools if allowed. Create 2+ AVDs (small phone, large phone, optionally tablet), verify they boot headless (-no-window -no-audio) and `adb devices` sees them. If a tool is unavailable (no Docker, no KVM, no emulator), record it, apply a fallback (native services, Robolectric/Roborazzi screenshot tests), and mark degraded verification steps in STATE.md so evidence stays honest.

## A4. MAIN LOOP (REPEAT UNTIL A9)
LOOP:
 1. PICK the first unblocked task in BACKLOG.md in phase order (Part B delivery plan, Part C stages at their scheduled positions including parallel track 1b, Part D/E items in Phase 0). If the current phase backlog is empty, expand it into granular tasks from the spec (including discovered P0/P1 features).
 2. DESIGN briefly (few lines in log; DECISIONS.md if non-obvious).
 3. IMPLEMENT the smallest testable vertical slice (data -> backend -> API -> Android/admin UI).
 4. VERIFY with the full Verification Protocol (Part B) and the CI Definition of Done (Part D10). Actually run commands. Read real output. OPEN and LOOK at real screenshots yourself.
 5. FIX failures. Up to 3 distinct fix attempts per failure, then diagnose differently (logs, bisect, simplify); after 6 total attempts mark the task blocked in BLOCKERS.md with a precise description, implement the best safe workaround or defer the slice, and MOVE ON. Never loop forever on one failure.
 6. PUSH and WATCH CI (`gh run watch`, `gh run view --log-failed`). Fix red runs under the same retry policy.
 7. RECORD evidence, update FEATURES.md, STATE.md, PROGRESS_LOG.md; commit.
 8. REPORT to me per the Visibility Contract (E8): 1-3 lines, plus the report link and key screenshots. No questions.
 9. If a phase finished: run the phase gate (all features done, full suite green, smoke e2e green, CI green, evidence present, docs updated), tag, begin the next phase. If a mesh stage finished: run its gate exactly as in Part C.
 10. GO TO 1.

## A5. DECISION POLICY (NO QUESTIONS)
- Ambiguity: choose the most standard, secure, free, user-friendly option; record alternatives in DECISIONS.md; continue.
- Missing credentials (SMTP, Telegram token, WhatsApp, signing keystore, staging SSH): implement the adapter behind its interface, ship a dev/mock adapter that logs OTPs to a dev inbox (dev/test profile only), document how to plug in real credentials, continue. Never fabricate real credentials.
- Risky/infeasible requirement: say so in DECISIONS.md, implement the best free alternative, continue.
- Never silently drop or weaken a requirement. Deferred items go to BACKLOG.md with a reason and are revisited before completion.
- Keep adding good features per the Feature Discovery Mandate; P0/P1 must be finished before the final phase.

## A6. HONESTY RULES (CRITICAL)
- Never mark a task done unless its verification actually ran and passed. Never claim a screenshot was reviewed if you did not view it.
- Mesh verification has three separate levels in FEATURES.md: simulation-verified, emulator-verified (simulated transport), physical-device-verified. Without physical devices never claim BLE is verified.
- Never fake results, hard-code outputs, disable tests, or loosen thresholds to get green. Justify any threshold change in DECISIONS.md.
- Report degraded verification (A3 fallbacks, "CI not run on GitHub", "no KVM") explicitly.

## A7. HARD STOPS (ONLY REASONS TO PAUSE)
Write to /BLOCKERS.md and send a final message ONLY if: (a) an action would be destructive/irreversible outside the project, cost money, or need real third-party credentials that cannot be mocked; (b) the environment cannot build anything and no fallback exists; (c) a legal/safety issue needs a human decision that cannot be defaulted safely; (d) you hit an execution/turn/token limit: first make STATE.md, BACKLOG.md and git fully current and committed, then end with: "RESUME: say 'continue' and I will pick up at <task>."
Everything else: decide, log, continue.

## A8. CONTEXT AND EFFICIENCY HYGIENE
Small tasks, frequent commits. Don't paste large files into chat. Keep logs/evidence on disk. Run long jobs in background with log files and poll. Re-read the relevant spec section at the start of each phase/stage. Keep the build reproducible: one command setup (make up), pinned versions, seeded demo data (admin, riders, drivers, trips, fare configs, small city map extract). Do not commit large binaries (videos, APKs) to git; use CI artifacts, Pages, or release assets.

## A9. COMPLETION CRITERIA (STOP ONLY WHEN ALL TRUE)
1. Every P0/P1 feature in FEATURES.md done with evidence; P2 done or explicitly deferred with reasons.
2. All Part B phases (0-11) and Part C stages (1-7) passed their gates; Stage 6 executed on 3+ physical devices OR marked "simulation/emulator-verified only" with the exact unverified list.
3. Full suite, smoke e2e, lint/static analysis green on a clean checkout via one command AND on GitHub Actions.
4. Release builds (APK/AAB, admin bundle, backend images) produced and installable.
5. All docs exist and are accurate (README, ARCHITECTURE, OpenAPI, guides, MESH_*, THREAT_MODEL, RUNBOOK, COMPETITIVE_AUDIT, DECISIONS, BLOCKERS, CI.md).
6. BACKLOG.md has no todo/doing items; blocked/deferred listed with reasons.
Then write /docs/FINAL_REPORT.md (built, evidence index, verified vs not, limitations, legal/regulatory notes, next steps) and send a final summary with links to the latest report gallery.

## A10. START NOW
No acknowledgements or plans. Run the Session Start Routine and execute Phase 0 immediately, then continue through every phase and stage.

################################################################################

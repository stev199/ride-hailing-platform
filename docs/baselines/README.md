# Compose screenshot baselines (Part E2)

Roborazzi/Paparazzi baselines for Compose screens live here (flat: `<screen>[-<variant>].png`).
Empty until the first Roborazzi screenshot test lands (P2-2 MapLibre main screen or the first
theming variant capture); the directory is the P2-3 deliverable so capture jobs can reference it.

Contract (docs/VISIBILITY_SPEC.md E2, tools/step-storyboard/README.md):

- CI produces expected/actual/diff triples into the run's report gallery; the filmstrip shows
  the diff image on drift so a reviewer sees what changed.
- A baseline change requires an explicit note in the commit/PR message - no silent regeneration.
- Baselines are captured locale-agnostic (expected text resolved from resources, P2-1b
  convention); variant captures (light/dark, EN/FR/AR-RTL, font scale 1.0/1.3) are driven by
  the nightly matrix and named `<screen>-<variant>.png`.

# One-command workflows (Part D D1.5, A8). Same commands locally and in CI.
.PHONY: help up up-test down test-backend test-admin test-android-ui e2e live viewer report ci-local seed lint

help:
	@echo "targets:"
	@echo "  up            - compose infra+geo profiles (needs Docker)"
	@echo "  up-test       - compose test profile w/ mocks (per-shard backend isolation)"
	@echo "  down          - stop compose"
	@echo "  test-backend  - backend unit+integration (Phase 1+)"
	@echo "  test-admin    - admin-web vitest+playwright (Phase 2+)"
	@echo "  test-android-ui  - sharded UI tests (Phase 2+)"
	@echo "  e2e           - cross-actor two-emulator scenarios (Phase 2+)"
	@echo "  live          - boot emulators + viewer + tests (Part E7)"
	@echo "  viewer        - Test Viewer replay/live server on :7070"
	@echo "  report        - build report gallery from /artifacts"
	@echo "  seed          - seed-test.sh with demo data"
	@echo "  ci-local      - local pipeline subset (no emulators needed)"

up:
	@command -v docker >/dev/null 2>&1 || { echo "Docker not available in this environment (recorded in STATE.md) - run on a dev machine or CI"; exit 1; }
	docker compose -f infra/docker-compose.yml --profile infra --profile geo up -d

up-test:
	@command -v docker >/dev/null 2>&1 || { echo "Docker not available in this environment - run on a dev machine or CI"; exit 1; }
	docker compose -f infra/docker-compose.yml --profile infra --profile app --profile test up -d
	./scripts/wait-for-health.sh

down:
	docker compose -f infra/docker-compose.yml --profile infra --profile geo --profile app --profile test down || true

test-backend:
	@if [ -d backend ]; then cd backend && ./gradlew test; else echo "backend module lands in Phase 1 - nothing to test yet"; fi

test-admin:
	@if [ -d admin-web ]; then cd admin-web && npm ci && npm test && npx playwright test; else echo "admin-web module lands in Phase 2 - nothing to test yet"; fi

test-android-ui:
	@if [ -d android ]; then ./scripts/start-emulators.sh 2 && ./scripts/run-ui-tests.sh all 0 1; else echo "android module lands in Phase 2 - nothing to test yet"; fi

e2e:
	@if [ -d e2e ] && [ -f e2e/package.json ]; then make up-test && cd e2e && npm ci && node orchestrator.js --scenario all; else echo "e2e orchestrator needs Phase 2 modules - skeleton only"; fi

live:
	@echo "Part E7: boots emulators, viewer, backend, admin; runs rider+driver+admin tests concurrently."
	@./scripts/ci-local.sh live || true

viewer:
	cd tools/test-viewer && npm install --no-audit --no-fund && node server.js

report:
	node tools/report/generate.js --artifacts artifacts --out artifacts/report

seed:
	./scripts/seed-test.sh default

ci-local:
	./scripts/ci-local.sh

lint:
	./scripts/ci-local.sh lint

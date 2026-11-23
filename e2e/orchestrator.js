// Cross-actor E2E orchestrator skeleton (D5). Real scenarios land with Phase 2/4/5 modules.
// Design: three actor drivers (rider instrumentation, driver instrumentation, Playwright admin)
// run concurrently, synchronized via backend test-only endpoint /test/barrier/{name} and this
// timeline. Emits timeline.json (time-aligned UI/API/DB/WS/admin events) consumed by the
// report gallery (E6) and the Test Viewer live panel (E4).
const fs = require("fs");

const scenario = process.argv.includes("--scenario")
  ? process.argv[process.argv.indexOf("--scenario") + 1]
  : "all";

const BACKEND_URL = process.env.BACKEND_URL || "http://10.0.2.2:8080";
const RIDER_SERIAL = process.env.RIDER_SERIAL || "emulator-5554";
const DRIVER_SERIAL = process.env.DRIVER_SERIAL || "emulator-5556";
const STEP_DELAY_MS = process.env.STEP_DELAY_MS || "0";
const STEP_MODE = process.env.STEP_MODE || "0";
const VIEWER_URL = process.env.VIEWER_URL || "";

const timeline = [];
function log(actor, event, detail) {
  const entry = { at: new Date().toISOString(), actor, event, detail: detail || "" };
  timeline.push(entry);
  console.log(`[${entry.at}] ${actor}: ${event} ${detail}`);
  if (VIEWER_URL) {
    fetch(`${VIEWER_URL}/api/log`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ source: actor, line: `${event} ${detail}` }),
    }).catch(() => {});
  }
}

async function barrier(name) {
  // backend test-profile endpoint; proves absence in release builds (D5.4, covered by backend test)
  log("orchestrator", "barrier", name);
  return fetch(`${BACKEND_URL}/test/barrier/${name}`).catch(() => log("orchestrator", "barrier-unreachable", name));
}

async function runScenario(name) {
  log("orchestrator", "scenario-start", name);
  await barrier(`${name}-start`);
  // Actor wiring lands per phase:
  //  - rider:  adb -s $RIDER_SERIAL shell am instrument (RiderE2E instrumentation, storyboard steps)
  //  - driver: adb -s $DRIVER_SERIAL shell am instrument (DriverE2E)
  //  - admin:  playwright with storyboard helper + live map page open
  log("orchestrator", "scenario-end-placeholder", "actors wire up as modules land (Phases 2-5)");
  await barrier(`${name}-end`);
}

(async () => {
  log("orchestrator", "config", `scenario=${scenario} delay=${STEP_DELAY_MS}ms stepMode=${STEP_MODE}`);
  const scenarios = scenario === "all"
    ? ["online-happy-path", "specific-driver-and-cancel", "scheduled-and-concurrent", "points-transfer", "doc-verification", "ban-and-config", "offline-mesh-booking", "driver-degraded", "flakiness-and-restore"]
    : [scenario];
  for (const s of scenarios) await runScenario(s);
  fs.mkdirSync("artifacts", { recursive: true });
  fs.writeFileSync("artifacts/timeline.json", JSON.stringify({ scenario, timeline }, null, 2));
  log("orchestrator", "timeline-written", "artifacts/timeline.json");
})();

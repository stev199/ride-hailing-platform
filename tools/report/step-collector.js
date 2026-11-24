#!/usr/bin/env node
// Step Storyboard collector (E4 host side): receives the androidTest StepStoryboard POSTs
// (base64 PNGs) and writes the gallery filmstrip layout directly on the runner.
// This is the reliable transport on aosp_atd: the device-side alternatives all fail
// (UiAutomation shell = app uid + SELinux denies /data/local/tmp; AGP uninstalls the app
// after connected tests, wiping /sdcard/Android/data/<pkg>; scoped storage denies plain
// adb pull of another app's dir) - while the emulator reaches the host at 10.0.2.2:7070
// regardless. See docs/VISIBILITY_SPEC.md E2/E4 and tools/step-storyboard/README.md.
// Usage: node tools/report/step-collector.js --port 7070 --out artifacts/ui-rider-0
const http = require("http");
const fs = require("fs");
const path = require("path");

function arg(name, def) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : def;
}
const port = parseInt(arg("--port", "7070"), 10);
const outDir = arg("--out", "artifacts");

// Mirrors com.ridehailing.test.storyboard.Slug (lowercase, [^a-z0-9]+ -> '-', trim, 'step').
const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "step";

const counters = new Map(); // testSlug -> next step index

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "content-type": "text/plain" });
    res.end("ok");
    return;
  }
  if (req.method !== "POST" || req.url !== "/api/step") {
    res.writeHead(404);
    res.end();
    return;
  }
  let body = "";
  req.on("data", (c) => { body += c; });
  req.on("end", () => {
    res.writeHead(200, { "content-type": "text/plain" });
    res.end("ok");
    let e;
    try { e = JSON.parse(body); } catch { return; }
    try {
      const testSlug = slug(e.testId || e.test || "test");
      const dir = path.join(outDir, "storyboard", slug(e.app || "unknown"), testSlug);
      fs.mkdirSync(dir, { recursive: true });
      const meta = { ...e, screenshot: undefined, at: new Date().toISOString() };
      fs.appendFileSync(path.join(dir, "timeline.jsonl"), JSON.stringify(meta) + "\n");
      if (!e.screenshot) return; // step/test-end event without a shot: timeline only
      const idx = String(counters.get(testSlug) ?? 0).padStart(2, "0");
      counters.set(testSlug, (counters.get(testSlug) ?? 0) + 1);
      const file = path.join(dir, `${idx}-${slug(e.step || "step")}.png`);
      fs.writeFileSync(file, Buffer.from(e.screenshot, "base64"));
    } catch (err) {
      console.error(`[step-collector] ${err.message}`);
    }
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`[step-collector] listening on :${port} -> ${outDir}`);
});

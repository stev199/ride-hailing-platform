// Test Viewer (Part E4) - live and replay modes, no paid services.
// LIVE: streams each connected adb device as MJPEG (screencap polling), accepts step()
// events from the Step Storyboard helper (POST /api/step), shows pass/fail counters and
// logcat tails. REPLAY: serves any run directory's storyboard + timeline.json.
// Token protection: set VIEWER_TOKEN - requests without ?token= or header are rejected.
const express = require("express");
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 7070;
const TOKEN = process.env.VIEWER_TOKEN || "";
const REPLAY_DIR = process.env.REPLAY_DIR || "";
const app = express();
app.use(express.json({ limit: "4mb" }));

function auth(req, res, next) {
  if (!TOKEN) return next();
  const t = req.query.token || req.header("x-viewer-token");
  if (t !== TOKEN) return res.status(401).send("viewer token required");
  next();
}
app.use(auth);

// ---------- state ----------
const state = {
  tests: {}, // testId -> { name, app, shard, status, steps: [], startedAt }
  counters: { passed: 0, failed: 0, running: 0 },
  logs: { backend: [], devices: {} },
};
const clients = new Set();
function broadcast(msg) {
  const data = JSON.stringify(msg);
  for (const c of clients) if (c.readyState === 1) c.send(data);
}

// ---------- API: storyboard step events (from androidTest helper / playwright) ----------
app.post("/api/step", (req, res) => {
  const { testId, test, app, shard, step, status, screenshot, event } = req.body || {};
  if (!testId) return res.status(400).json({ error: "testId required" });
  let t = state.tests[testId];
  if (!t) {
    t = state.tests[testId] = {
      name: test || testId, app: app || "unknown", shard: shard ?? 0,
      status: "running", steps: [], startedAt: new Date().toISOString(),
    };
    state.counters.running++;
  }
  if (event === "test-end") {
    t.status = status || "done";
    t.endedAt = new Date().toISOString();
    state.counters.running = Math.max(0, state.counters.running - 1);
    if (status === "passed") state.counters.passed++;
    if (status === "failed") state.counters.failed++;
  } else if (step) {
    t.steps.push({ step, at: new Date().toISOString(), screenshot: screenshot || null });
  }
  broadcast({ type: "step", testId, test: t });
  res.json({ ok: true });
});

app.post("/api/log", (req, res) => { // backend/device log tails
  const { source, device, line } = req.body || {};
  const key = device || source || "backend";
  const buf = state.logs.devices[key] || (state.logs.devices[key] = []);
  buf.push(`${new Date().toISOString()} ${line || ""}`);
  if (buf.length > 500) buf.shift();
  broadcast({ type: "log", key, line });
  res.json({ ok: true });
});

app.get("/api/state", (req, res) => res.json(state));

// ---------- MJPEG: adb screencap polling per serial ----------
const mjpegStreams = new Map();
app.get("/stream/:serial", (req, res) => {
  const serial = req.params.serial;
  res.writeHead(200, {
    "Content-Type": "multipart/x-mixed-replace; boundary=frame",
    "Cache-Control": "no-store",
  });
  let timer = null;
  const push = async () => {
    try {
      const proc = spawn("adb", ["-s", serial, "exec-out", "screencap", "-p"]);
      let buf = Buffer.alloc(0);
      proc.stdout.on("data", (d) => (buf = Buffer.concat([buf, d])));
      proc.on("close", () => {
        if (buf.length > 8 && res.writable) {
          res.write(`--frame\r\nContent-Type: image/png\r\nContent-Length: ${buf.length}\r\n\r\n`);
          res.write(buf);
          res.write("\r\n");
        }
      });
      proc.on("error", () => {});
    } catch (_) { /* device gone */ }
  };
  timer = setInterval(push, 700); // ~1.5 fps base; scrcpy upgrade path documented in README
  mjpegStreams.set(serial, timer);
  req.on("close", () => { clearInterval(timer); mjpegStreams.delete(serial); });
});

app.get("/api/serials", (req, res) => {
  // adb may be absent (viewer used for REPLAY-only runs / non-Android hosts): an
  // unhandled spawn 'error' event CRASHED the whole server here - the web view died
  // instead of degrading (caught by the round-11 local smoke). Answer the empty list.
  let p;
  try {
    p = spawn("adb", ["devices"]);
  } catch (_) {
    return res.json({ serials: [], replayDir: REPLAY_DIR || null });
  }
  let out = "";
  let answered = false;
  const answer = (serials) => {
    if (answered) return;
    answered = true;
    res.json({ serials, replayDir: REPLAY_DIR || null });
  };
  p.stdout.on("data", (d) => (out += d));
  p.on("error", () => answer([]));
  p.on("close", () => {
    const serials = out.split("\n").filter((l) => /device\s*$/.test(l)).map((l) => l.split("\t")[0]);
    answer(serials);
  });
});

// ---------- static UI ----------
app.get("/", (req, res) => {
  res.type("html").send(`<!doctype html><html><head><meta charset="utf-8">
<title>ride-hailing Test Viewer</title>
<style>
 body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0}
 h1{font-size:1.2rem;padding:.8rem 1rem;margin:0;background:#1e293b}
 #grid{display:flex;flex-wrap:wrap;gap:12px;padding:12px}
 .panel{background:#1e293b;border-radius:8px;padding:8px;min-width:320px}
 .panel h3{margin:.2rem 0 .5rem;font-size:.9rem}
 img{max-width:100%;border-radius:4px;background:#000}
 #tests{padding:0 12px 20px}
 table{border-collapse:collapse;width:100%}
 td,th{border:1px solid #334155;padding:4px 8px;font-size:.85rem;text-align:left}
 .pass{color:#4ade80}.fail{color:#f87171}.run{color:#facc15}
 #counters{padding:.5rem 1rem;font-size:.95rem}
</style></head><body>
<h1>ride-hailing-platform Test Viewer</h1>
<div id="counters">loading…</div>
<div id="grid"></div>
<div id="tests"><table id="tbl"><tr><th>test</th><th>app</th><th>shard</th><th>status</th><th>last step</th><th>steps</th></tr></table></div>
<script>
async function refresh(){
  const s = await (await fetch('/api/state')).json();
  document.getElementById('counters').innerHTML =
    'passed: <b class="pass">'+s.counters.passed+'</b> failed: <b class="fail">'+s.counters.failed+
    '</b> running: <b class="run">'+s.counters.running+'</b>';
  const r = await (await fetch('/api/serials')).json();
  const grid = document.getElementById('grid');
  if(!grid.childElementCount || grid.dataset.sig!==r.serials.join(',')){
    grid.dataset.sig=r.serials.join(','); grid.innerHTML='';
    for(const ser of r.serials){
      const d=document.createElement('div'); d.className='panel';
      d.innerHTML='<h3>device '+ser+'</h3><img src="/stream/'+ser+'" alt="screen '+ser+'">';
      grid.appendChild(d);
    }
  }
  const tb=document.getElementById('tbl');
  const rows=Object.entries(s.tests).map(([id,t])=>
    '<tr><td>'+t.name+'</td><td>'+t.app+'</td><td>'+t.shard+'</td><td class="'+t.status+'">'+t.status+
    '</td><td>'+(t.steps.length?t.steps[t.steps.length-1].step:'-')+'</td><td>'+t.steps.length+'</td></tr>');
  tb.innerHTML='<tr><th>test</th><th>app</th><th>shard</th><th>status</th><th>last step</th><th>steps</th></tr>'+rows.join('');
}
refresh(); setInterval(refresh,2000);
</script></body></html>`);
});

// ---------- REPLAY mode: serve a run directory's storyboard statically ----------
if (REPLAY_DIR && fs.existsSync(REPLAY_DIR)) {
  app.use("/replay", express.static(REPLAY_DIR));
  app.get("/replay-index", (req, res) => {
    const runs = fs.readdirSync(REPLAY_DIR).filter((f) => fs.statSync(path.join(REPLAY_DIR, f)).isDirectory());
    res.json({ runs });
  });
}

const server = http.createServer(app);
const wss = new WebSocketServer({ server });
wss.on("connection", (c) => { clients.add(c); c.on("close", () => clients.delete(c)); });

server.listen(PORT, () => console.log(`Test Viewer on http://localhost:${PORT}${TOKEN ? " (token protected)" : ""}${REPLAY_DIR ? ` replay=${REPLAY_DIR}` : ""}`));

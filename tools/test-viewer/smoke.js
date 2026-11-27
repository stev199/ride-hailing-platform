#!/usr/bin/env node
// Round-21 smoke: reproduce the "stuck at loading" user scenario end-to-end.
// The tunnel URL is /?token=X; the BROWSER then fetches /api/state etc. WITHOUT
// the token in the URL (cookie must carry it). Run: node smoke.js
const { spawn } = require("child_process");
const assert = require("assert");

const PORT = process.env.SMOKE_PORT || 7071;
const TOKEN = "smoke-" + Math.random().toString(16).slice(2);
const BASE = `http://127.0.0.1:${PORT}`; // explicit v4: no localhost/DNS subtleties
const T = (ms) => AbortSignal.timeout(ms); // every fetch bounded: a hang is a FAIL, not a stall

function child() {
  return spawn(process.execPath, ["server.js"], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(PORT), VIEWER_TOKEN: TOKEN },
    stdio: ["ignore", "pipe", "pipe"],
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log("[smoke]", ...a);

async function waitHealthy(proc) {
  for (let i = 0; i < 50; i++) {
    if (proc.exitCode !== null) throw new Error("server exited early: " + proc.exitCode);
    try {
      const res = await fetch(`${BASE}/api/state?token=${TOKEN}`, { signal: T(2000) });
      if (res.ok) return log("healthy after", i * 0.2, "s");
    } catch (_) { /* not up yet */ }
    await wait(200);
  }
  throw new Error("server never became healthy");
}

(async () => {
  const proc = child();
  proc.stdout.on("data", (d) => process.stdout.write("[srv-out] " + d));
  proc.stderr.on("data", (d) => process.stdout.write("[srv-err] " + d));
  proc.on("exit", (c) => log("server exited", c));
  let cookie = "";
  try {
    await waitHealthy(proc);

    log("1. no token -> 401");
    const noTok = await fetch(`${BASE}/api/state`, { signal: T(5000) });
    assert.strictEqual(noTok.status, 401, "unauthenticated /api/state must 401");

    log("2. wrong token -> 401");
    const bad = await fetch(`${BASE}/?token=WRONG`, { signal: T(5000) });
    assert.strictEqual(bad.status, 401, "wrong token must 401");

    log("3. page with the generated URL (?token=OK)");
    const page = await fetch(`${BASE}/?token=${TOKEN}`, { signal: T(5000) });
    assert.strictEqual(page.status, 200, "page with token must 200");
    const html = await page.text();
    assert.ok(html.includes("tk('/api/state')"), "page JS must propagate the token");
    const setCookie = page.headers.get("set-cookie") || "";
    assert.ok(setCookie.includes(`viewer_token=${TOKEN}`), "page load must set the cookie");
    cookie = (setCookie.match(/viewer_token=[^;]+/) || [])[0];
    assert.ok(cookie, "cookie parseable");

    log("4. in-page fetch with ONLY the cookie (what the browser sends)");
    const viaCookie = await fetch(`${BASE}/api/state`, { headers: { cookie }, signal: T(5000) });
    assert.strictEqual(viaCookie.status, 200, "/api/state via cookie must 200");
    const st = await viaCookie.json();
    assert.ok(st.counters && typeof st.counters.passed === "number", "state JSON shape");

    log("5. /api/serials via cookie");
    const serials = await fetch(`${BASE}/api/serials`, { headers: { cookie }, signal: T(5000) });
    assert.strictEqual(serials.status, 200, "/api/serials via cookie must 200");
    assert.ok(Array.isArray((await serials.json()).serials), "serials JSON shape");

    log("6. MJPEG stream via cookie (headers only; frames flow when devices exist)");
    const stream = await fetch(`${BASE}/stream/smoke-fake`, { headers: { cookie }, signal: T(5000) });
    assert.strictEqual(stream.status, 200, "/stream via cookie must 200");
    assert.ok((stream.headers.get("content-type") || "").includes("multipart"), "multipart MJPEG");
    await stream.body?.cancel(); // the server holds this open by design

    log("7. explicit query token still works everywhere (CI health path)");
    const viaQuery = await fetch(`${BASE}/api/state?token=${TOKEN}`, { signal: T(5000) });
    assert.strictEqual(viaQuery.status, 200, "/api/state via query token must 200");

    log("SMOKE PASS: page load -> cookie -> in-page fetches all authorized");
  } finally {
    proc.kill("SIGTERM");
  }
  process.exit(0);
})().catch((e) => {
  console.error("SMOKE FAIL:", e.message);
  process.exit(1);
});

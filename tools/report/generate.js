#!/usr/bin/env node
// Report gallery generator (Part E6) - builds a static, self-contained site per run.
// Inputs: --artifacts <dir> (per-shard dirs with summary.json/storyboards) + run metadata
// from GITHUB_* env or CLI flags. Output: index.html + per-test cards; also deployed to
// Pages under /runs/<run-id>/ and /latest/ (last 30 runs) by ci.yml report-publish.
const fs = require("fs");
const path = require("path");

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : def;
}
const artifactsDir = arg("--artifacts", "artifacts");
const outDir = arg("--out", "artifacts/report");
const runId = arg("--run-id", process.env.GITHUB_RUN_ID || `local-${Date.now()}`);
const runUrl = arg(
  "--run-url",
  process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY && process.env.GITHUB_RUN_ID
    ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}`
    : "local run"
);
const sha = process.env.GITHUB_SHA || "local";

function walk(dir, depth = 0) {
  if (!fs.existsSync(dir) || depth > 4) return [];
  return fs.readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return fs.statSync(p).isDirectory() ? [p, ...walk(p, depth + 1)] : [p];
  });
}

const files = walk(artifactsDir).filter((p) => !p.includes(`${path.sep}report`));
const images = files.filter((p) => /\.(png|jpg|webp)$/i.test(p));
const videos = files.filter((p) => /\.(mp4|webm)$/i.test(p));
const xmls = files.filter((p) => /\.xml$/i.test(p));
const summaries = files.filter((p) => p.endsWith("summary.json"));

const shardCards = summaries
  .map((f) => {
    try {
      const s = JSON.parse(fs.readFileSync(f, "utf8"));
      return `<div class="card"><h3>${path.basename(path.dirname(f))}</h3>
      <table><tr><td>tests</td><td>${s.tests ?? "-"}</td></tr>
      <tr><td>passed</td><td>${s.passed ?? "-"}</td></tr>
      <tr><td>failed</td><td>${s.failed ?? "-"}</td></tr>
      <tr><td>flaky</td><td>${(s.flaky || []).join(", ") || "-"}</td></tr></table></div>`;
    } catch (e) {
      return `<div class="card"><h3>${path.basename(path.dirname(f))}</h3><p>summary unreadable: ${e.message}</p></div>`;
    }
  })
  .join("\n");

const filmstrip = images
  .slice(0, 60)
  .map((p) => {
    const rel = path.relative(artifactsDir, p);
    return `<figure><img src="${rel}" alt="${rel}" loading="lazy"><figcaption>${rel}</figcaption></figure>`;
  })
  .join("\n");

const videoList = videos
  .slice(0, 20)
  .map((p) => {
    const rel = path.relative(artifactsDir, p);
    return `<figure><video controls src="${rel}"></video><figcaption>${rel}</figcaption></figure>`;
  })
  .join("\n");

const junitList = xmls.map((p) => `<li>${path.relative(artifactsDir, p)}</li>`).join("");

const html = `<!doctype html><html><head><meta charset="utf-8">
<title>ride-hailing report ${runId}</title>
<style>
 :root{color-scheme:light dark}
 body{font-family:system-ui,sans-serif;max-width:1100px;margin:0 auto;padding:1rem}
 h1{font-size:1.4rem}h3{margin:.4rem 0}
 .card{border:1px solid #9994;border-radius:8px;padding:.7rem;margin:.6rem 0;min-width:240px;flex:1}
 .cards{display:flex;flex-wrap:wrap;gap:.6rem}
 figure{display:inline-block;margin:.4rem;text-align:center;max-width:240px;vertical-align:top}
 figure img,figure video{max-width:240px;border:1px solid #9994;border-radius:4px}
 figcaption{font-size:.7rem;color:#888;word-break:break-all}
 table{border-collapse:collapse}td,th{border:1px solid #9994;padding:.3rem .5rem;text-align:left}
 .meta{color:#666;font-size:.85rem}
</style></head><body>
<h1>ride-hailing-platform - test report</h1>
<p class="meta">run <a href="${runUrl}">${runId}</a> &middot; commit <code>${sha}</code> &middot; generated ${new Date().toISOString()}</p>
<div class="cards">${shardCards || '<div class="card">No summary.json yet - component test suites land in Phases 1-2 (see STATE.md).</div>'}</div>
<h2>Filmstrip (storyboard screenshots)</h2>
${filmstrip || '<p>No screenshots in this run.</p>'}
<h2>Videos</h2>
${videoList || '<p>No videos in this run.</p>'}
<h2>JUnit XML</h2>
<ul>${junitList || '<li>none</li>'}</ul>
<p class="meta">Self-contained: open this file locally; media paths are relative to the artifacts root.</p>
</body></html>`;

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, "index.html"), html);
console.log(`report written: ${path.join(outDir, "index.html")} (${images.length} images, ${videos.length} videos, ${summaries.length} summaries)`);

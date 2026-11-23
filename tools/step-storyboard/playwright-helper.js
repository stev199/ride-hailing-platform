// Playwright twin of the Android StepStoryboard helper (Part E2).
// Usage in admin-web Playwright tests (wired in Phase 2):
//   const s = storyboard(page, { testId, test, app: 'admin', shard: 0 });
//   await s.step('Open live map', async () => { await page.goto('/admin/live'); });
const fs = require("fs");
const path = require("path");

function slugify(s) { return s.toLowerCase().replace(/[^a-z0-9]+/g, "-"); }

function storyboard(page, { testId, test, app = "admin", shard = 0, artifactsDir = "artifacts", viewerUrl = process.env.VIEWER_URL }) {
  const dir = path.join(artifactsDir, app, String(shard), testId);
  fs.mkdirSync(dir, { recursive: true });
  let counter = 0;
  async function post(payload) {
    if (!viewerUrl) return;
    try { await fetch(`${viewerUrl}/api/step`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload) }); } catch (_) {}
  }
  return {
    async step(name, fn, { before = false } = {}) {
      if (before) await page.screenshot({ path: path.join(dir, `pre-${slugify(name)}.png`) });
      try {
        await fn();
        counter += 1;
        const file = path.join(dir, `${String(counter).padStart(2, "0")}-${slugify(name)}.png`);
        await page.screenshot({ path: file });
        await post({ testId, test, app, shard, step: name, screenshot: fs.existsSync(file) ? fs.readFileSync(file).toString("base64") : null });
      } catch (e) {
        await page.screenshot({ path: path.join(dir, `FAIL-${slugify(name)}.png`) });
        fs.writeFileSync(path.join(dir, "FAIL-reason.txt"), String(e && e.stack || e));
        await post({ testId, test, app, shard, step: `FAIL ${name}` });
        throw e;
      }
    },
    async finish(status) { await post({ testId, test, app, shard, event: "test-end", status }); },
  };
}
module.exports = { storyboard };

import { defineConfig, devices } from '@playwright/test';

/**
 * P1-4 admin E2E. The API is mocked at the network layer (page.route in
 * helpers/mockApi.ts) - self-contained, no compose stack needed. The
 * compose-backed Playwright run against the real backend lands with P0-C
 * (first-green pipeline; DECISIONS D67).
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  workers: 2,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }], ['json', { outputFile: 'artifacts/admin-playwright.json' }]]
    : [['list'], ['json', { outputFile: 'artifacts/admin-playwright.json' }]],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});

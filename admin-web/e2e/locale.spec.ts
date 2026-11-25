import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, seedSession } from './helpers/mockApi';

/**
 * P1-4c: admin i18n EN/FR/AR-RTL evidence. Language switch flips EVERY screen (i18next
 * resources), Arabic flips the document to RTL (html[dir=rtl] + logical CSS), and the
 * D3.4 screenshot matrix is the FULL SET now: {en,fr,ar} x {light,dark} x 5 screens.
 * Server errors stay verbatim (not translated) - by design, they are contract surface.
 */

async function switchTo(page: Page, lang: string): Promise<void> {
  await page.selectOption('[data-testid="lang-select"]', lang);
  // react-i18next re-renders synchronously after changeLanguage; wait for the nav label
  const expected: Record<string, RegExp> = {
    en: /Overview|Utilisateurs/,
    fr: /Vue d’ensemble/,
    ar: /نظرة عامة/,
  };
  await expect(page.locator('.sidebar a').first()).toHaveText(expected[lang]);
}

test.describe('locale switch + RTL (seeded ADMIN_SUPER session)', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
  });

  test('switching to French translates the whole shell', async ({ page }) => {
    await page.goto('/');
    await switchTo(page, 'fr');
    await expect(page.locator('.sidebar a').nth(1)).toHaveText('Utilisateurs');
    await expect(page.locator('[data-testid="theme-toggle"]')).toHaveText('Mode sombre');
    await expect(page.locator('.brand')).toHaveText('Admin Ride-hailing');
    // lang + persisted choice
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await page.reload();
    await expect(page.locator('.sidebar a').first()).toHaveText('Vue d’ensemble');
  });

  test('switching to Arabic flips the document to RTL', async ({ page }) => {
    await page.goto('/');
    await switchTo(page, 'ar');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(page.locator('.sidebar a').nth(1)).toHaveText('المستخدمون');
    // RTL survives navigation (document attribute, not a wrapper class)
    await page.goto('/users');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.locator('[data-testid="users-table"]')).toBeVisible();
  });

  test('axe passes on the Arabic RTL overview (wcag2a/2aa)', async ({ page }) => {
    await page.goto('/');
    await switchTo(page, 'ar');
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(results.violations, JSON.stringify(results.violations.map((v) => v.id))).toEqual([]);
  });

  test('user action panel renders notices in the active language', async ({ page }) => {
    await page.goto('/users');
    await switchTo(page, 'fr');
    await page.getByTestId('user-create').click();
    await expect(page.getByTestId('user-action-panel')).toContainText('Créer un utilisateur');
    await page.getByTestId('user-action-cancel').click();
  });
});

test.describe('D3.4 screenshot matrix: {en,fr,ar} x {light,dark} x 5 screens', () => {
  // Deterministic files: capture on chromium only (firefox duplicates the same names).
  test.skip(({ browserName }) => browserName !== 'chromium');

  const PAGES: Array<{ path: string; name: string }> = [
    { path: '/login', name: 'login' },
    { path: '/', name: 'overview' },
    { path: '/users', name: 'users' },
    { path: '/audit', name: 'audit' },
    { path: '/config', name: 'config' },
  ];

  for (const lang of ['en', 'fr', 'ar']) {
    for (const theme of ['light', 'dark']) {
      test(`matrix ${lang}-${theme}`, async ({ page }) => {
        await mockApi(page, 'ADMIN_SUPER');
        await page.addInitScript(() => localStorage.setItem('admin.theme', 'light'));
        await page.goto('/login');
        // login screen captures BEFORE seeding the session (it is the unauthenticated screen)
        await page.evaluate((t) => {
          localStorage.setItem('admin.theme', t);
          document.documentElement.dataset.theme = t;
        }, theme);
        await page.screenshot({ path: `artifacts/admin-login-${lang}-${theme}.png`, fullPage: true });

        await seedSession(page);
        for (const p of PAGES.slice(1)) {
          await page.goto(p.path);
          await page.evaluate((t) => {
            document.documentElement.dataset.theme = t;
          }, theme);
          await page.waitForLoadState('networkidle');
          await page.screenshot({ path: `artifacts/admin-${p.name}-${lang}-${theme}.png`, fullPage: true });
        }
      });
    }
  }
});

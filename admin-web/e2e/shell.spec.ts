import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, seedSession } from './helpers/mockApi';

test.describe('shell (seeded ADMIN_SUPER session)', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
  });

  test('audit tail renders events and the verified-chain banner', async ({ page }) => {
    await page.goto('/audit');
    await expect(page.getByTestId('verify-banner')).toContainText('Hash chain verified');
    const rows = page.getByTestId('audit-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.first()).toContainText('admin.user.updated');
  });

  test('config center v0 shows the empty state (endpoints land with config service)', async ({ page }) => {
    await page.goto('/config');
    await expect(page.getByTestId('config-empty-state')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Planned sections' })).toBeVisible();
  });

  test('logout returns to the login screen and clears the session', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('logout').click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });

  test.describe('axe accessibility checks', () => {
    async function expectNoAxeViolations(page: import('@playwright/test').Page, path: string): Promise<void> {
      await page.goto(path);
      await expect(page.getByTestId('role-badge')).toBeVisible();
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
      expect(results.violations, `${path}: ${JSON.stringify(results.violations.map((v) => v.id))}`).toEqual([]);
    }

    test('overview page', async ({ page }) => {
      await expectNoAxeViolations(page, '/');
    });
    test('users page', async ({ page }) => {
      await expectNoAxeViolations(page, '/users');
    });
    test('audit page', async ({ page }) => {
      await expectNoAxeViolations(page, '/audit');
    });
    test('config page', async ({ page }) => {
      await expectNoAxeViolations(page, '/config');
    });
  });

  test.describe('theme toggle (light/dark evidence screenshots, D3.4 subset)', () => {
    test('shell renders in both themes', async ({ page }) => {
      await page.goto('/');
      await expect(page.getByTestId('role-badge')).toBeVisible();
      await page.screenshot({ path: 'artifacts/admin-overview-light.png', fullPage: true });
      await page.getByTestId('theme-toggle').click();
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
      await page.screenshot({ path: 'artifacts/admin-overview-dark.png', fullPage: true });
    });
  });
});

test.describe('login page (fresh session)', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
  });

  test('axe accessibility check (unauthenticated)', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(results.violations).toEqual([]);
  });
});

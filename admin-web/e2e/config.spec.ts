import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mockApi, seedSession, resetUsers, resetConfig, getLastConfigPatch } from './helpers/mockApi';

/**
 * P1-5a2 config editors (route-mocked per D68): the CONFIG_WRITE editor PATCHes only
 * the changed fields, server validation errors surface verbatim (duplicate_priority),
 * CONFIG_READ-only roles get no editor, and the editor is axe-clean + dark-evidenced.
 */
test.describe('config editors (seeded writable session)', () => {
  test.beforeEach(() => {
    resetUsers();
    resetConfig();
  });

  test('patches only the changed fields and bumps the signed version', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/config');
    await expect(page.getByTestId('config-current-version')).toContainText('Current config version: 2');

    await page.getByTestId('config-edit').click();
    await expect(page.getByTestId('config-edit-panel')).toBeVisible();
    // Untouched editor: Save stays disabled (an empty patch is a server 400 by contract).
    await expect(page.getByTestId('config-edit-submit')).toBeDisabled();

    await page.getByTestId('cfg-toggle-password').click();
    await page.getByTestId('cfg-enabled-TELEGRAM').click();
    await page.getByTestId('config-edit-submit').click();

    await expect(page.getByTestId('config-action-notice')).toContainText('signed config version');
    await expect(page.getByTestId('config-current-version')).toContainText('Current config version: 3');
    await expect(page.getByTestId('config-history-3')).toContainText('login_methods_updated');
    expect(getLastConfigPatch()).toEqual({
      passwordLoginEnabled: false,
      otpChannels: { TELEGRAM: { enabled: true } },
    });
  });

  test('duplicate priority surfaces the server 400 verbatim and keeps editing', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/config');
    await page.getByTestId('config-edit').click();

    // SMS is enabled at priority 10; enabling TELEGRAM at 10 collides.
    await page.getByTestId('cfg-enabled-TELEGRAM').click();
    await page.getByTestId('cfg-priority-TELEGRAM').fill('10');
    await page.getByTestId('config-edit-submit').click();

    await expect(page.getByTestId('config-edit-error')).toContainText('duplicate_priority');
    await expect(page.getByTestId('config-edit-panel')).toBeVisible();
    await expect(page.getByTestId('config-current-version')).toContainText('Current config version: 2');
  });

  test('editor is axe-clean (wcag2a/2aa) with the dialog open', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/config');
    await page.getByTestId('config-edit').click();
    await expect(page.getByTestId('config-edit-panel')).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(results.violations, JSON.stringify(results.violations.map((v) => v.id))).toEqual([]);
  });

  test('dark-mode evidence of the editor (batch tier, D69)', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/config');
    await page.getByTestId('config-edit').click();
    await expect(page.getByTestId('config-edit-panel')).toBeVisible();
    await page.getByTestId('theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.screenshot({ path: 'artifacts/admin-config-edit-dark.png', fullPage: true });
  });

  test('ADMIN_SUPPORT (CONFIG_READ only) sees the state but no editor', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPPORT');
    await seedSession(page);
    await page.goto('/config');
    await expect(page.getByTestId('config-methods-table')).toBeVisible();
    await expect(page.getByTestId('config-edit')).toHaveCount(0);
  });
});

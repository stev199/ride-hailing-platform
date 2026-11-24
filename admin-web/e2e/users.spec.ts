import { test, expect } from '@playwright/test';
import { mockApi, seedSession, resetUsers } from './helpers/mockApi';

/**
 * P1-4b users mutations (route-mocked per D68): create / edit / ban / reset-password /
 * force-logout, server-error surfacing and the RBAC-driven button visibility.
 */
test.describe('users mutations (seeded ADMIN_SUPER/ADMIN_OPS session)', () => {
  test.beforeEach(() => {
    resetUsers();
  });

  test('creates a user and refreshes the list', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/users');
    await page.getByTestId('user-create').click();
    await page.getByTestId('ua-display-name').fill('Nia New');
    await page.getByTestId('ua-phone').fill('15550003333');
    await page.getByTestId('ua-password').fill('new-user-pass-1');
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-notice')).toContainText('User "Nia New" created');
    await expect(page.getByTestId('user-row')).toHaveCount(4);
  });

  test('surfaces the server 409 identifier_taken verbatim', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/users');
    await page.getByTestId('user-create').click();
    await page.getByTestId('ua-display-name').fill('Dup Person');
    await page.getByTestId('ua-phone').fill('15550001111');
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-error')).toContainText('identifier_taken');
    await expect(page.getByTestId('user-action-panel')).toBeVisible();
  });

  test('ban presets BANNED_TEMP and the edit round-trips the row', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/users');
    await page.getByTestId('user-ban-u3').click();
    await expect(page.getByTestId('user-action-panel')).toContainText('Edit user — Dan Driver');
    await expect(page.getByTestId('ua-status')).toHaveValue('BANNED_TEMP');
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-notice')).toContainText('User "Dan Driver" updated');
    const row = page.getByTestId('user-row').filter({ hasText: 'Dan Driver' });
    await expect(row).toContainText('BANNED_TEMP');
  });

  test('reset password reports the revocation side effect', async ({ page }) => {
    await mockApi(page, 'ADMIN_OPS');
    await seedSession(page);
    await page.goto('/users');
    await page.getByTestId('user-reset-u2').click();
    await page.getByTestId('ua-new-password').fill('brand-new-pass-1');
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-notice')).toContainText('all sessions revoked');
  });

  test('force logout reports revoked sessions; self force-logout shows the server refusal', async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPER');
    await seedSession(page);
    await page.goto('/users');
    await page.getByTestId('user-logout-u3').click();
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-notice')).toContainText('2 session(s) revoked');
    await page.getByTestId('user-logout-u1').click();
    await page.getByTestId('user-action-submit').click();
    await expect(page.getByTestId('user-action-error')).toContainText('self_force_logout');
  });

  test('ADMIN_READONLY sees the list but no action buttons', async ({ page }) => {
    await mockApi(page, 'ADMIN_READONLY');
    await seedSession(page);
    await page.goto('/users');
    await expect(page.getByTestId('users-table')).toBeVisible();
    await expect(page.getByTestId('user-create')).toHaveCount(0);
    await expect(page.getByText('read-only role').first()).toBeVisible();
  });
});

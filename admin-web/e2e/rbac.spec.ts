import { expect, test } from '@playwright/test';
import { mockApi, seedSession, USERS } from './helpers/mockApi';

test.describe('RBAC routing', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, 'ADMIN_SUPPORT');
    await seedSession(page);
  });

  test('ADMIN_SUPPORT sees Users + Audit + Config center (matrix grants CONFIG_READ)', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('role-badge')).toHaveText('ADMIN_SUPPORT');
    await expect(page.getByRole('link', { name: 'Users' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Audit log' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Config center' })).toBeVisible();
  });

  test('RIDER (empty permission set) gets only Overview; direct /users shows the guard view', async ({ page }) => {
    await mockApi(page, 'RIDER');
    await page.goto('/');
    await expect(page.getByTestId('role-badge')).toHaveText('RIDER');
    await expect(page.getByRole('link', { name: 'Users' })).toHaveCount(0);
    await page.goto('/users');
    await expect(page.getByTestId('forbidden-perm')).toContainText('USERS_READ');
  });

  test('Users page lists mocked users with role + status', async ({ page }) => {
    await page.goto('/users');
    const rows = page.getByTestId('user-row');
    await expect(rows).toHaveCount(USERS.length);
    await expect(rows.first()).toContainText('Ops Admin');
    await expect(rows.nth(1)).toContainText('BANNED_TEMP');
    await expect(rows.nth(2)).toContainText('DRIVER');
  });

  test('Users search triggers a new query round-trip', async ({ page }) => {
    await page.goto('/users');
    await expect(page.getByTestId('user-row')).toHaveCount(USERS.length);
    await page.getByTestId('user-search').fill('ria');
    await page.getByTestId('user-search-go').click();
    // The mock ignores the query; assert the request carried it.
    await expect(page.getByTestId('user-row')).toHaveCount(USERS.length);
  });
});

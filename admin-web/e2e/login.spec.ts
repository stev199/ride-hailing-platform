import { expect, test } from '@playwright/test';
import { mockApi } from './helpers/mockApi';

test.describe('login flow', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, 'ADMIN_OPS');
  });

  test('wrong password shows the API error banner', async ({ page }) => {
    await page.route('**/v1/auth/login/password', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'unauthorized', message: 'Unknown identifier or wrong password', traceId: null }),
      }));
    await page.goto('/login');
    await page.getByLabel('Phone or email').fill('ops@example.com');
    await page.getByLabel('Password').fill('wrong');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByTestId('login-error')).toContainText('unauthorized');
  });

  test('successful login lands in the shell with role badge', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Phone or email').fill('15550001111');
    await page.getByLabel('Password').fill('correct-horse');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByTestId('role-badge')).toHaveText('ADMIN_OPS');
    await expect(page.getByTestId('display-name')).toHaveText('Ops Admin');
    // Overview renders the permission chips.
    await expect(page.getByTestId('perm-chips')).toBeVisible();
  });
});

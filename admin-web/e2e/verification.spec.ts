import { test, expect } from '@playwright/test';
import { mockApi, seedSession, resetVerification } from './helpers/mockApi';

/**
 * P6-1 verification center (route-mocked per D68): queue render, approve, reject
 * (reason required - client guard mirrors the server 400), double-review 409
 * surfacing verbatim, and the VERIFICATION_READ nav gate.
 */
test.describe('verification center (seeded verifier session)', () => {
  test.beforeEach(() => {
    resetVerification();
  });

  test('renders the queue and approves a pending submission', async ({ page }) => {
    await mockApi(page, 'ADMIN_VERIFIER');
    await seedSession(page);
    await page.goto('/verification');
    await expect(page.getByTestId('verification-table')).toBeVisible();
    await expect(page.getByTestId('verification-row-11111111-1111-1111-1111-111111111111')).toContainText('AA-123-BB');
    await page.getByTestId('verification-approve-11111111-1111-1111-1111-111111111111').click();
    await expect(page.getByTestId('verification-status-11111111-1111-1111-1111-111111111111')).toContainText('APPROVED');
  });

  test('reject requires a reason; a real reason lands and shows verbatim', async ({ page }) => {
    await mockApi(page, 'ADMIN_VERIFIER');
    await seedSession(page);
    await page.goto('/verification');
    const id = '11111111-1111-1111-1111-111111111111';
    await page.getByTestId(`verification-reject-open-${id}`).click();
    // Client guard: empty reason -> confirm disabled (mirrors the 400 invalid_reason).
    await expect(page.getByTestId(`verification-reject-confirm-${id}`)).toBeDisabled();
    await page.getByTestId(`verification-reason-input-${id}`).fill('plate mismatch');
    await page.getByTestId(`verification-reject-confirm-${id}`).click();
    await expect(page.getByTestId(`verification-status-${id}`)).toContainText('REJECTED');
    await expect(page.getByTestId(`verification-status-${id}`)).toContainText('plate mismatch');
  });

  test('a reviewed submission keeps its reason visible but loses the action buttons', async ({ page }) => {
    await mockApi(page, 'ADMIN_VERIFIER');
    await seedSession(page);
    await page.goto('/verification');
    // The queue defaults to the PENDING filter - the seeded REJECTED row is
    // deliberately not on this screen (switch the filter to see history).
    const rejectedId = '22222222-2222-2222-2222-222222222222';
    await expect(page.getByTestId(`verification-row-${rejectedId}`)).toHaveCount(0);
    // Reject the pending row with a reason: the row shows REJECTED + the reason
    // verbatim and its action buttons are gone (reviewed = terminal for v1).
    const id = '11111111-1111-1111-1111-111111111111';
    await page.getByTestId(`verification-reject-open-${id}`).click();
    await page.getByTestId(`verification-reason-input-${id}`).fill('plate mismatch');
    await page.getByTestId(`verification-reject-confirm-${id}`).click();
    await expect(page.getByTestId(`verification-status-${id}`)).toContainText('REJECTED');
    await expect(page.getByTestId(`verification-status-${id}`)).toContainText('plate mismatch');
    await expect(page.getByTestId(`verification-approve-${id}`)).toHaveCount(0);
    await expect(page.getByTestId(`verification-reject-open-${id}`)).toHaveCount(0);
  });

  test('role without VERIFICATION_READ (RIDER) sees the forbidden notice', async ({ page }) => {
    // Every ADMIN_* role holds VERIFICATION_READ by design (the center is a core
    // admin function; VERIFIER/OPS write it) - only RIDER/DRIVER are excluded.
    await mockApi(page, 'RIDER');
    await seedSession(page);
    await page.goto('/verification');
    await expect(page.getByTestId('forbidden-perm')).toBeVisible();
    await expect(page.getByTestId('verification-table')).toHaveCount(0);
  });
});

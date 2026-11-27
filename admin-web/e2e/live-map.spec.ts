import { test, expect } from '@playwright/test';
import { mockApi, seedSession } from './helpers/mockApi';

/**
 * P6-4 admin live map (route-mocked per D68): the nav gate (TRIPS_READ),
 * the snapshot render (counts meta + driver/trip rows), and the empty state.
 * Real tiles/markers stay with the compose geo stack (nightly cells) - these
 * tests prove the DATA layer in the route-mocked browser.
 */
const LIVE = {
  drivers: [
    { driverId: 'driver-1', lat: 43.7384, lon: 7.4246, online: true, updatedAtEpochSec: 1764200000 },
  ],
  trips: [
    {
      id: 'aaaaaaaa-1111-2222-3333-444444444444', status: 'REQUESTED', city: 'monaco',
      riderId: 'rider-1', driverId: null,
      pickupLat: 43.7384, pickupLon: 7.4246, dropoffLat: 43.7543, dropoffLon: 7.4312,
      origin: 'online',
    },
  ],
};

test.describe('admin live map (seeded ops session)', () => {
  test('nav gate: TRIPS_READ role sees the link, page renders the snapshot', async ({ page }) => {
    await page.route('**/v1/admin/live', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(LIVE) }),
    );
    await mockApi(page, 'ADMIN_OPS');
    await seedSession(page);
    await page.goto('/');
    await expect(page.getByRole('link', { name: 'Live map' })).toBeVisible();
    await page.getByRole('link', { name: 'Live map' }).click();
    await expect(page).toHaveURL(/\/live$/);
    await expect(page.getByTestId('live-map')).toBeVisible();
    await expect(page.getByTestId('live-meta')).toContainText('1 on duty');
    await expect(page.getByTestId('live-meta')).toContainText('1 active trips');
    await expect(page.getByTestId('live-driver-driver-1')).toContainText('43.73840');
    await expect(page.getByTestId('live-trip-aaaaaaaa-1111-2222-3333-444444444444')).toContainText('REQUESTED');
  });

  test('empty snapshot shows the empty state text', async ({ page }) => {
    await page.route('**/v1/admin/live', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ drivers: [], trips: [] }) }),
    );
    await mockApi(page, 'ADMIN_OPS');
    await seedSession(page);
    await page.goto('/live');
    await expect(page.getByTestId('live-empty')).toBeVisible();
    await expect(page.getByTestId('live-drivers')).toContainText('No drivers on duty');
    await expect(page.getByTestId('live-trips')).toContainText('No active trips');
  });
});

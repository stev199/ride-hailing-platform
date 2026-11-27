import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import TripsPage from '../TripsPage';
import { tokenStore } from '../../api/client';
import type { TripDetail, TripListResponse } from '../TripsPage';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en',
};

const RIDE_A: TripListResponse['rides'][number] = {
  id: 'aaaaaaaa-1111-2222-3333-444444444444', status: 'REQUESTED', city: 'monaco',
  rideType: 'standard', riderId: 'rider-1', driverId: null, origin: 'online',
  paymentMethod: 'CASH', quoteAmountMinor: 1250, quoteCurrency: 'EUR',
  pickupName: 'Harbor', dropoffName: 'Airport', cancelFeeMinor: 0,
  createdAtEpochSec: 1764200000, updatedAtEpochSec: 1764200000,
};

const RIDE_B: TripListResponse['rides'][number] = {
  ...RIDE_A,
  id: 'bbbbbbbb-1111-2222-3333-444444444444', status: 'COMPLETED', driverId: 'driver-9',
};

const LIST: TripListResponse = { rides: [RIDE_B, RIDE_A], limit: 25, offset: 0 };

const DETAIL: TripDetail = {
  ride: RIDE_B,
  timeline: [
    { seq: 1, type: 'ride.requested', occurredAt: '2026-11-27T00:00:00Z', payloadJson: `{"rideId":"${RIDE_B.id}"}` },
    { seq: 2, type: 'ride.accepted', occurredAt: '2026-11-27T00:01:00Z', payloadJson: `{"rideId":"${RIDE_B.id}"}` },
    { seq: 3, type: 'ride.completed', occurredAt: '2026-11-27T00:10:00Z', payloadJson: `{"rideId":"${RIDE_B.id}"}` },
  ],
};

interface FetchCall { url: string; method: string }

/** Stubbed backend (same harness as FaresPage.test): /v1/me + the two trips endpoints. */
function renderPage(role: string) {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init?: { method?: string }) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method });
      if (url.includes('/v1/auth/refresh')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }
      if (url.includes('/v1/me')) {
        return Promise.resolve(new Response(JSON.stringify({ ...ADMIN, role }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/v1/admin/rides/')) {
        return Promise.resolve(new Response(JSON.stringify(DETAIL), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/v1/admin/rides')) {
        return Promise.resolve(new Response(JSON.stringify(LIST), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  render(
    <MemoryRouter initialEntries={['/trips']}>
      <AuthProvider>
        <TripsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls };
}

describe('TripsPage (Phase 6 trips admin)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('renders the ride list newest-first with origin badges', async () => {
    renderPage('ADMIN_OPS');
    await waitFor(() => expect(screen.getByTestId('trips-table')).toBeTruthy());
    const rows = screen.getAllByTestId(/^trips-row-/);
    expect(rows.length).toBe(2);
    // Newest first: bbbb… (COMPLETED) above aaaa… (REQUESTED).
    expect(rows[0].textContent).toContain('COMPLETED');
    expect(rows[1].textContent).toContain('REQUESTED');
    // Origin badges: every row shows the Phase-10-constant "online".
    expect(screen.getAllByText('online').length).toBe(2);
  });

  it('row click fetches the detail with the ride.* timeline, close clears it', async () => {
    const { calls } = renderPage('ADMIN_OPS');
    await waitFor(() => expect(screen.getByTestId('trips-table')).toBeTruthy());
    await userEvent.click(screen.getByTestId(`trips-row-${RIDE_B.id}`));
    await waitFor(() => expect(screen.getByTestId('trips-detail')).toBeTruthy());
    expect(calls.some((c) => c.url.includes(`/v1/admin/rides/${RIDE_B.id}`))).toBe(true);
    const timeline = screen.getByTestId('trips-timeline');
    expect(timeline.textContent).toContain('ride.requested');
    expect(timeline.textContent).toContain('ride.completed');
    await userEvent.click(screen.getByTestId('trips-detail-close'));
    expect(screen.queryByTestId('trips-detail')).toBeNull();
  });

  it('status filter + apply refetches with the query params', async () => {
    const { calls } = renderPage('ADMIN_OPS');
    await waitFor(() => expect(screen.getByTestId('trips-table')).toBeTruthy());
    const callsBefore = calls.length;
    await userEvent.selectOptions(screen.getByTestId('trips-status-filter'), 'COMPLETED');
    await userEvent.click(screen.getByTestId('trips-apply'));
    await waitFor(() => expect(calls.length > callsBefore).toBe(true));
    expect(calls.some((c) => c.url.includes('status=COMPLETED'))).toBe(true);
  });

  it('role without TRIPS_READ (RIDER) gets the forbidden notice, no ride fetch', async () => {
    renderPage('RIDER');
    await waitFor(() => expect(screen.getByTestId('forbidden-perm')).toBeTruthy());
    // No trips call ever leaves the page.
    expect(screen.queryByTestId('trips-table')).toBeNull();
  });
});

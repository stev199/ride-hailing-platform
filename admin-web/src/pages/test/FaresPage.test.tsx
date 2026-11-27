import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import FaresPage from '../FaresPage';
import { tokenStore } from '../../api/client';
import type { FareConfig } from '../FaresPage';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en',
};

const DOC: FareConfig = {
  cities: {
    monaco: {
      currency: 'EUR',
      rideTypes: {
        standard: {
          base: 2.5, perKm: 1.8, perMinute: 0.45, minimumFare: 8.0,
          bookingFee: 1.5, cancelFee: 3.0, waitFeePerMinute: 0.5,
          night: { multiplier: 1.2, startHourUtc: 22, endHourUtc: 5 },
          peak: { multiplier: 1.5, startHourUtc: 8, endHourUtc: 10 },
        },
      },
    },
  },
  straightLine: { detourFactor: 1.3, assumedAvgSpeedKmh: 22.0 },
  quote: { validitySeconds: 90, mode: 'upfront_locked', meteredCapMultiplier: 1.5 },
};

interface FetchCall { url: string; method: string; body: unknown }

/**
 * Stubbed backend (same harness as ConfigPage.test): refresh + /v1/me boot the
 * shell, GET/PATCH /v1/admin/config/fares. `patchResponder` decides the PATCH
 * answer per test; every request is recorded in `calls`.
 */
function renderPage(role: string, opts: { patchResponder?: () => { status: number; body: unknown } } = {}) {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init?: { method?: string; body?: string }) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body ? JSON.parse(init.body) : null });
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
      if (url.includes('/v1/admin/config/fares')) {
        if (method === 'PATCH') {
          const r = opts.patchResponder?.() ?? { status: 200, body: DOC };
          return Promise.resolve(new Response(JSON.stringify(r.body), { status: r.status, headers: { 'Content-Type': 'application/json' } }));
        }
        return Promise.resolve(new Response(JSON.stringify(DOC), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  const page = render(
    <MemoryRouter initialEntries={['/fares']}>
      <AuthProvider>
        <FaresPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls, page };
}

describe('FaresPage (Phase 6 fares editor)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('renders the per-city grid with the loaded fees', async () => {
    renderPage('ADMIN_OPS');
    await waitFor(() => expect(screen.getByTestId('fares-city-monaco')).toBeTruthy());
    const row = screen.getByTestId('fares-row-monaco-standard');
    expect(row.textContent).toContain('2.5');
    expect(row.textContent).toContain('×1.2 (22–5 UTC)');
    expect(screen.getByTestId('fares-quote-policy').textContent).toContain('upfront_locked');
  });

  it('read-only role (no CONFIG_WRITE) sees no edit button', async () => {
    renderPage('ADMIN_SUPPORT');
    await waitFor(() => expect(screen.getByTestId('fares-city-monaco')).toBeTruthy());
    expect(screen.queryByTestId('fares-edit')).toBeNull();
  });

  it('sends the WHOLE document on save (D75 full-replace) and reloads', async () => {
    const { calls } = renderPage('ADMIN_SUPER');
    await waitFor(() => expect(screen.getByTestId('fares-city-monaco')).toBeTruthy());

    await userEvent.click(screen.getByTestId('fares-edit'));
    const input = screen.getByTestId('in-cities.monaco.rideTypes.standard.base') as HTMLInputElement;
    expect(input.value).toBe('2.5');
    await userEvent.clear(input);
    await userEvent.type(input, '3.25');
    await userEvent.click(screen.getByTestId('fares-edit-submit'));

    await waitFor(() => expect(screen.getByTestId('fares-notice')).toBeTruthy());
    const patch = calls.find((c) => c.method === 'PATCH');
    expect(patch).toBeTruthy();
    // FULL-REPLACE: the body carries every schema key at every level.
    expect(patch!.body).toMatchObject({
      cities: {
        monaco: {
          currency: 'EUR',
          rideTypes: {
            standard: {
              base: 3.25, perKm: 1.8, perMinute: 0.45, minimumFare: 8,
              bookingFee: 1.5, cancelFee: 3, waitFeePerMinute: 0.5,
              night: { multiplier: 1.2, startHourUtc: 22, endHourUtc: 5 },
              peak: { multiplier: 1.5, startHourUtc: 8, endHourUtc: 10 },
            },
          },
        },
      },
      straightLine: { detourFactor: 1.3, assumedAvgSpeedKmh: 22 },
      quote: { validitySeconds: 90, mode: 'upfront_locked', meteredCapMultiplier: 1.5 },
    });
  });

  it('surfaces a server 400 verbatim and blocks negative input client-side', async () => {
    const { calls } = renderPage('ADMIN_SUPER', {
      patchResponder: () => ({
        status: 400,
        body: { code: 'invalid_fares', message: 'base must be positive', traceId: null },
      }),
    });
    await waitFor(() => expect(screen.getByTestId('fares-city-monaco')).toBeTruthy());

    await userEvent.click(screen.getByTestId('fares-edit'));
    // Negative amount (non-hour field) -> client-side block, no PATCH sent.
    const input = screen.getByTestId('in-cities.monaco.rideTypes.standard.base') as HTMLInputElement;
    await userEvent.clear(input);
    await userEvent.type(input, '-1');
    await userEvent.click(screen.getByTestId('fares-edit-submit'));
    expect(screen.getByTestId('fares-edit-error').textContent).toContain('invalid_input');
    expect(calls.filter((c) => c.method === 'PATCH').length).toBe(0);

    // Valid number but server rejects -> verbatim error.
    await userEvent.clear(input);
    await userEvent.type(input, '3');
    await userEvent.click(screen.getByTestId('fares-edit-submit'));
    await waitFor(() => expect(screen.getByTestId('fares-edit-error').textContent).toContain('base must be positive'));
    expect(calls.filter((c) => c.method === 'PATCH').length).toBe(1);
  });
});

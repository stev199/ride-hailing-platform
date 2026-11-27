import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import LiveMapPage from '../LiveMapPage';
import { tokenStore } from '../../api/client';
import type { LiveSnapshot } from '../LiveMapPage';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en',
};

const SNAPSHOT: LiveSnapshot = {
  drivers: [
    { driverId: 'driver-1', lat: 43.7384, lon: 7.4246, online: true, updatedAtEpochSec: 1764200000 },
    { driverId: 'driver-2', lat: 43.75, lon: 7.44, online: true, updatedAtEpochSec: 1764200050 },
  ],
  trips: [
    {
      id: 'aaaaaaaa-1111-2222-3333-444444444444', status: 'REQUESTED', city: 'monaco',
      riderId: 'rider-1', driverId: null,
      pickupLat: 43.7384, pickupLon: 7.4246, dropoffLat: 43.7543, dropoffLon: 7.4312,
      origin: 'online',
    },
    {
      id: 'bbbbbbbb-1111-2222-3333-444444444444', status: 'IN_PROGRESS', city: 'monaco',
      riderId: 'rider-2', driverId: 'driver-1',
      pickupLat: 43.74, pickupLon: 7.43, dropoffLat: 43.76, dropoffLon: 7.45,
      origin: 'online',
    },
  ],
};

// maplibre-gl is mocked: the unit suite asserts the DATA layer (snapshot fetch,
// lists, gates) - real map rendering is browser-level (playwright) work.
vi.mock('maplibre-gl', () => {
  class FakeMap {
    on() {}
    once() {}
    isStyleLoaded() {
      return true;
    }
    remove() {}
  }
  class FakeMarker {
    setLngLat() {
      return this;
    }
    addTo() {
      return this;
    }
    remove() {}
  }
  return { default: { Map: FakeMap, Marker: FakeMarker, NavigationControl: class {} } };
});

/** Stubbed backend (TripsPage.test harness): /v1/me + GET /v1/admin/live. */
function renderPage(role: string, snapshot: LiveSnapshot | null, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string) => {
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
      if (url.includes('/v1/admin/live')) {
        return Promise.resolve(
          new Response(JSON.stringify(snapshot ?? { code: 'boom', message: 'kaputt', traceId: null }), {
            status,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  render(
    <MemoryRouter initialEntries={['/live']}>
      <AuthProvider>
        <LiveMapPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('LiveMapPage (P6-4 admin live map)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('renders the snapshot: counts meta + one row per driver and per active trip', async () => {
    renderPage('ADMIN_OPS', SNAPSHOT);
    await waitFor(() => expect(screen.getByTestId('live-meta').textContent).toContain('2'));
    expect(screen.getByTestId('live-meta').textContent).toContain('2 on duty');
    expect(screen.getByTestId('live-meta').textContent).toContain('2 active trips');
    expect(screen.getByTestId('live-driver-driver-1').textContent).toContain('43.73840');
    expect(screen.getByTestId('live-trip-aaaaaaaa-1111-2222-3333-444444444444').textContent).toContain('REQUESTED');
    expect(screen.getByTestId('live-trip-bbbbbbbb-1111-2222-3333-444444444444').textContent).toContain('IN_PROGRESS');
    // The map container exists (degraded environments keep the text lists).
    expect(screen.getByTestId('live-map')).toBeTruthy();
    expect(screen.queryByTestId('live-empty')).toBeNull();
  });

  it('empty snapshot shows the empty state', async () => {
    renderPage('ADMIN_OPS', { drivers: [], trips: [] });
    await waitFor(() => expect(screen.getByTestId('live-empty')).toBeTruthy());
    expect(screen.getByTestId('live-drivers').textContent).toContain('No drivers on duty');
    expect(screen.getByTestId('live-trips').textContent).toContain('No active trips');
  });

  it('server errors surface verbatim (code: message)', async () => {
    renderPage('ADMIN_OPS', null, 500);
    await waitFor(() => expect(screen.getByTestId('live-error')).toBeTruthy());
    expect(screen.getByTestId('live-error').textContent).toContain('boom: kaputt');
  });

  it('a role without TRIPS_READ gets the forbidden gate and no live fetch', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/v1/auth/refresh')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }
      if (url.includes('/v1/me')) {
        return Promise.resolve(new Response(JSON.stringify({ ...ADMIN, role: 'RIDER' }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    });
    vi.stubGlobal('fetch', fetchMock);
    localStorage.setItem('admin.refreshToken', 'rt');
    sessionStorage.setItem('admin.accessToken', 'at');
    render(
      <MemoryRouter initialEntries={['/live']}>
        <AuthProvider>
          <LiveMapPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByTestId('forbidden-perm')).toBeTruthy());
    expect(screen.getByTestId('forbidden-perm').textContent).toContain('TRIPS_READ');
    await new Promise((r) => setTimeout(r, 25));
    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/v1/admin/live')).length).toBe(0);
  });
});

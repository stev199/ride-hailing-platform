import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Admin live map (P6-4, spec "Live map: duty drivers + active trips"):
 * GET /v1/admin/live (TRIPS_READ) polled every LIVE_POLL_MS - on-duty drivers
 * (positions from the duty registry, the driver's own duty-toggle coordinates;
 * no GPS stream until the location work) + ACTIVE trips at their pickup
 * coordinates, plotted on MapLibre with DOM markers. The lists below the map
 * carry the same information as text (a11y + degraded-map environments), and
 * mesh-origin filtering (MESH_READ view) lands with Phase 10 - the snapshot
 * answers origin="online" until then. Read-only by design.
 */

export interface LiveDriver {
  driverId: string;
  lat: number;
  lon: number;
  online: boolean;
  updatedAtEpochSec: number;
}

export interface LiveTrip {
  id: string;
  status: string;
  city: string;
  riderId: string;
  driverId: string | null;
  pickupLat: number;
  pickupLon: number;
  dropoffLat: number;
  dropoffLon: number;
  origin: string;
}

export interface LiveSnapshot {
  drivers: LiveDriver[];
  trips: LiveTrip[];
}

export const LIVE_POLL_MS = 10_000;

/** Tiles come from the self-hosted geo stack (compose profile `geo`); overridable at build time. */
const STYLE_URL =
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_MAP_STYLE_URL ??
  'http://localhost:8081/styles/basic-preview/style.json';

const DRIVER_COLOR = '#1d4ed8';
const TRIP_STATUS_COLOR: Record<string, string> = {
  REQUESTED: '#b45309',
  ACCEPTED: '#0f766e',
  ARRIVED: '#4d7c0f',
  IN_PROGRESS: '#1d4ed8',
};

export default function LiveMapPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [snapshot, setSnapshot] = useState<LiveSnapshot | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loadedOnce, setLoadedOnce] = useState(false);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<unknown>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const markersRef = useRef<any[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const canRead = !!me && can(me.role, Perm.TRIPS_READ);

  const load = useCallback(async () => {
    if (!canRead) return;
    try {
      const res = await api.get<LiveSnapshot>('/v1/admin/live');
      setSnapshot(res);
      setError(null);
      setLoadedOnce(true);
    } catch (err) {
      setError(err as ApiError);
    }
  }, [api, canRead]);

  useEffect(() => {
    void load();
    // Poll: schedule the NEXT fetch only after the current one settles, so a
    // slow response never stacks; the timer dies with the effect (unmount).
    let alive = true;
    const tick = () => {
      if (!alive) return;
      void load().finally(() => {
        if (alive) timerRef.current = setTimeout(tick, LIVE_POLL_MS);
      });
    };
    timerRef.current = setTimeout(tick, LIVE_POLL_MS);
    return () => {
      alive = false;
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [load]);

  // Map lifecycle: created once, markers rebuilt per snapshot. A failing style
  // URL must NEVER take the page down - the text lists carry the same data.
  useEffect(() => {
    if (!canRead) return;
    let disposed = false;
    void (async () => {
      try {
        const maplibregl = (await import('maplibre-gl')).default;
        if (disposed || !containerRef.current) return;
        const map = new maplibregl.Map({
          container: containerRef.current,
          style: STYLE_URL,
          center: [7.4246, 43.7384],
          zoom: 11,
          attributionControl: false,
        });
        map.on('error', () => { /* tiles/style unavailable - lists stay authoritative */ });
        mapRef.current = map;
      } catch {
        /* module or WebGL unavailable - degraded text-only view */
      }
    })();
    return () => {
      disposed = true;
      markersRef.current.forEach((m) => m.remove?.());
      markersRef.current = [];
      const map = mapRef.current as { remove?: () => void } | null;
      map?.remove?.();
      mapRef.current = null;
    };
  }, [canRead]);

  // Rebuild markers whenever the snapshot changes.
  useEffect(() => {
    const map = mapRef.current as
      | { addControl?: (c: unknown) => void; isStyleLoaded?: () => boolean; once?: (e: string, cb: () => void) => void }
      | null;
    if (!snapshot || !map) return;
    const rebuild = async () => {
      try {
        const maplibregl = (await import('maplibre-gl')).default;
        markersRef.current.forEach((m) => m.remove?.());
        markersRef.current = [];
        for (const d of snapshot.drivers) {
          const el = document.createElement('div');
          el.setAttribute('data-testid', 'live-marker-driver');
          el.style.cssText = `width:14px;height:14px;border-radius:50%;background:${DRIVER_COLOR};border:2px solid #fff;box-shadow:0 0 4px rgba(0,0,0,.4);`;
          const marker = new maplibregl.Marker({ element: el })
            .setLngLat([d.lon, d.lat])
            .addTo(map as never);
          markersRef.current.push(marker);
        }
        for (const tr of snapshot.trips) {
          const el = document.createElement('div');
          el.setAttribute('data-testid', 'live-marker-trip');
          el.style.cssText = `width:18px;height:18px;border-radius:4px;background:${TRIP_STATUS_COLOR[tr.status] ?? '#555'};border:2px solid #fff;color:#fff;font-size:10px;line-height:14px;text-align:center;`;
          el.textContent = tr.status === 'REQUESTED' ? 'R' : 'T';
          const marker = new maplibregl.Marker({ element: el })
            .setLngLat([tr.pickupLon, tr.pickupLat])
            .addTo(map as never);
          markersRef.current.push(marker);
        }
      } catch {
        /* markers are decoration - the lists stay authoritative */
      }
    };
    if (map.isStyleLoaded?.()) void rebuild();
    else map.once?.('load', () => void rebuild());
  }, [snapshot]);

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="TRIPS_READ" />;

  const drivers = snapshot?.drivers ?? [];
  const trips = snapshot?.trips ?? [];
  const empty = loadedOnce && !error && drivers.length === 0 && trips.length === 0;

  return (
    <div>
      <h1 className="page-title">{t('live.title')}</h1>
      {error && (
        <div className="error-banner" data-testid="live-error">
          {error.code}: {error.message}
        </div>
      )}
      <div
        data-testid="live-map"
        ref={containerRef}
        style={{ height: 380, width: '100%', border: '1px solid var(--border, #ccc)', borderRadius: 8, background: 'var(--muted, #eee)' }}
      />
      <p className="muted" data-testid="live-meta">
        {t('live.refreshEvery')}
        {snapshot ? ` - ${t('live.counts', { drivers: drivers.length, trips: trips.length })}` : ''}
      </p>

      {empty && (
        <p className="muted" data-testid="live-empty">
          {t('live.empty')}
        </p>
      )}

      <section aria-label={t('live.driversTitle')} data-testid="live-drivers">
        <h2>{t('live.driversTitle')} ({drivers.length})</h2>
        {drivers.length === 0 ? (
          <p className="muted">{t('live.noDrivers')}</p>
        ) : (
          <ul>
            {drivers.map((d) => (
              <li key={d.driverId} data-testid={`live-driver-${d.driverId}`}>
                {t('live.driverAt', {
                  lat: d.lat.toFixed(5),
                  lon: d.lon.toFixed(5),
                })}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label={t('live.tripsTitle')} data-testid="live-trips">
        <h2>{t('live.tripsTitle')} ({trips.length})</h2>
        {trips.length === 0 ? (
          <p className="muted">{t('live.noTrips')}</p>
        ) : (
          <ul>
            {trips.map((tr) => (
              <li key={tr.id} data-testid={`live-trip-${tr.id}`}>
                {tr.status} - {tr.city} - {t('live.pickup')}: {tr.pickupLat.toFixed(5)}, {tr.pickupLon.toFixed(5)}
                {tr.origin !== 'online' ? ` - ${tr.origin}` : ''}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

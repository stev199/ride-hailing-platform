import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Trips admin (Phase 6 "Trips: live and historical", delivery plan item 6):
 * GET /v1/admin/rides (TRIPS_READ) with status/city filters and offset pagination;
 * a row opens GET /v1/admin/rides/{id} - the full ride plus the ride.* timeline
 * from the hash-chained event store. The `origin` field is the spec's admin-only
 * "online / offline-mesh" marker ("online" until mesh rides land, Phase 10).
 *
 * P7-5b money actions (server is the authority; the buttons are UX only):
 * - Cancel (TRIPS_WRITE): any non-terminal ride, fee-free; a pending WALLET hold is
 *   released server-side.
 * - Refund (MONEY_WRITE): COMPLETED WALLET rides only - exact capture reversal.
 * Errors surface VERBATIM (code + message).
 */

const NON_TERMINAL = new Set(['REQUESTED', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS']);

export interface TripListItem {
  id: string;
  status: string;
  city: string;
  rideType: string;
  riderId: string;
  driverId: string | null;
  origin: string;
  paymentMethod: string;
  quoteAmountMinor: number;
  quoteCurrency: string;
  pickupName: string | null;
  dropoffName: string | null;
  cancelFeeMinor: number;
  createdAtEpochSec: number;
  updatedAtEpochSec: number;
}

export interface TripTimelineEvent {
  seq: number;
  type: string;
  occurredAt: string;
  payloadJson: string;
}

export interface TripDetail {
  ride: TripListItem;
  timeline: TripTimelineEvent[];
}

export interface TripListResponse {
  rides: TripListItem[];
  limit: number;
  offset: number;
}

/** P7-5a POST /v1/admin/rides/{id}/cancel response. */
export interface TripActionResponse {
  rideId: string;
  status: string;
}

/** P7-5a POST /v1/admin/rides/{id}/refund response. */
export interface TripRefundResponse {
  rideId: string;
  riderGotMinor: number;
  driverDebitedMinor: number;
  commissionReversedMinor: number;
}

/** The backend RideStatus enum names (mirror of RideStatus.kt). */
const STATUS_FILTERS = [
  'REQUESTED',
  'ACCEPTED',
  'ARRIVED',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED_BY_RIDER',
  'CANCELLED_BY_DRIVER',
  'CANCELLED_BY_ADMIN',
  'CANCELLED_TIMEOUT',
  'EXPIRED',
] as const;

const PAGE_SIZE = 25;

function money(minor: number, currency: string): string {
  return `${(minor / 100).toFixed(2)} ${currency}`;
}

export default function TripsPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [rides, setRides] = useState<TripListItem[]>([]);
  const [status, setStatus] = useState<string>('');
  const [city, setCity] = useState<string>('');
  const [cityInput, setCityInput] = useState<string>('');
  const [offset, setOffset] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadedOnce, setLoadedOnce] = useState(false);
  const [detail, setDetail] = useState<TripDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [actionBusyId, setActionBusyId] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const canRead = !!me && can(me.role, Perm.TRIPS_READ);
  const canCancel = !!me && can(me.role, Perm.TRIPS_WRITE);
  const canRefund = !!me && can(me.role, Perm.MONEY_WRITE);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      const q = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (status) q.set('status', status);
      if (city) q.set('city', city);
      const res = await api.get<TripListResponse>(`/v1/admin/rides?${q.toString()}`);
      setRides(res.rides);
      setLoadedOnce(true);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }, [api, canRead, offset, status, city]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="TRIPS_READ" />;

  const openDetail = async (id: string) => {
    setDetailBusy(true);
    setError(null);
    try {
      setDetail(await api.get<TripDetail>(`/v1/admin/rides/${id}`));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setDetailBusy(false);
    }
  };

  const cancelRide = async (id: string) => {
    setActionBusyId(id);
    setError(null);
    setActionNotice(null);
    try {
      const res = await api.post<TripActionResponse>(`/v1/admin/rides/${id}/cancel`);
      setActionNotice(t('trips.cancelled', { id: res.rideId.slice(0, 8), status: res.status }));
      await load();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setActionBusyId(null);
    }
  };

  const refundRide = async (id: string) => {
    setActionBusyId(id);
    setError(null);
    setActionNotice(null);
    try {
      const res = await api.post<TripRefundResponse>(`/v1/admin/rides/${id}/refund`);
      setActionNotice(
        t('trips.refunded', {
          rider: String(res.riderGotMinor),
          driver: String(res.driverDebitedMinor),
          commission: String(res.commissionReversedMinor),
        }),
      );
      await load();
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setActionBusyId(null);
    }
  };

  return (
    <div>
      <h1 className="page-title">{t('trips.title')}</h1>
      {error && (
        <div className="error-banner" data-testid="trips-error">
          {error.code}: {error.message}
        </div>
      )}
      {actionNotice && (
        <div className="card" role="status" data-testid="trips-action-notice">
          {actionNotice}
        </div>
      )}
      <div className="search-row">
        <select
          aria-label={t('trips.statusFilter')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setOffset(0);
          }}
          data-testid="trips-status-filter"
        >
          <option value="">{t('trips.allStatuses')}</option>
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <input
          type="text"
          placeholder={t('trips.cityFilter')}
          aria-label={t('trips.cityFilter')}
          value={cityInput}
          onChange={(e) => setCityInput(e.target.value)}
          data-testid="trips-city-input"
        />
        <button
          onClick={() => {
            setCity(cityInput.trim());
            setOffset(0);
          }}
          data-testid="trips-apply"
        >
          {t('trips.apply')}
        </button>
        {busy && <span className="muted">{t('trips.loading')}</span>}
      </div>
      {loadedOnce && (
        <table className="data" aria-label={t('trips.title')} data-testid="trips-table">
          <thead>
            <tr>
              <th>{t('trips.id')}</th>
              <th>{t('trips.status')}</th>
              <th>{t('trips.city')}</th>
              <th>{t('trips.type')}</th>
              <th>{t('trips.quote')}</th>
              <th>{t('trips.origin')}</th>
              <th>{t('trips.driver')}</th>
              {(canCancel || canRefund) && <th>{t('trips.actions')}</th>}
            </tr>
          </thead>
          <tbody>
            {rides.map((r) => (
              <tr
                key={r.id}
                onClick={() => void openDetail(r.id)}
                style={{ cursor: 'pointer' }}
                data-testid={`trips-row-${r.id}`}
              >
                <td className="mono">{r.id.slice(0, 8)}…</td>
                <td>{r.status}</td>
                <td>{r.city}</td>
                <td>{r.rideType}</td>
                <td>{money(r.quoteAmountMinor, r.quoteCurrency)}</td>
                <td>
                  <span className="badge">{r.origin}</span>
                </td>
                <td>{r.driverId ? r.driverId.slice(0, 8) + '…' : '—'}</td>
                {(canCancel || canRefund) && (
                  <td onClick={(e) => e.stopPropagation()}>
                    {canCancel && NON_TERMINAL.has(r.status) && (
                      <button
                        onClick={() => void cancelRide(r.id)}
                        disabled={actionBusyId === r.id}
                        data-testid={`trips-cancel-${r.id}`}
                      >
                        {t('trips.cancel')}
                      </button>
                    )}{' '}
                    {canRefund && r.status === 'COMPLETED' && r.paymentMethod === 'WALLET' && (
                      <button
                        onClick={() => void refundRide(r.id)}
                        disabled={actionBusyId === r.id}
                        data-testid={`trips-refund-${r.id}`}
                      >
                        {t('trips.refund')}
                      </button>
                    )}
                  </td>
                )}
              </tr>
            ))}
            {rides.length === 0 && (
              <tr>
                <td colSpan={8} className="muted" data-testid="trips-empty">
                  {t('trips.empty')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
      <div className="search-row">
        <button
          onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          disabled={offset === 0}
          data-testid="trips-prev"
        >
          {t('trips.prev')}
        </button>
        <span className="muted" data-testid="trips-offset">
          {offset}
        </span>
        <button onClick={() => setOffset(offset + PAGE_SIZE)} disabled={rides.length < PAGE_SIZE} data-testid="trips-next">
          {t('trips.next')}
        </button>
      </div>
      {(detail || detailBusy) && (
        <div className="card" role="dialog" aria-label={t('trips.detailTitle')} data-testid="trips-detail">
          {detailBusy && <p className="muted">{t('trips.loading')}</p>}
          {detail && (
            <>
              <h2 style={{ marginTop: 0, fontSize: 14 }} data-testid="trips-detail-title">
                {detail.ride.id} · {detail.ride.status} · {detail.ride.city}
              </h2>
              <p className="muted" data-testid="trips-detail-meta">
                {detail.ride.rideType} · {money(detail.ride.quoteAmountMinor, detail.ride.quoteCurrency)} ·{' '}
                {t('trips.origin')}: <span className="badge">{detail.ride.origin}</span> · {detail.ride.paymentMethod}
                {detail.ride.pickupName ? ` · ${detail.ride.pickupName} → ${detail.ride.dropoffName ?? ''}` : ''}
              </p>
              <h3 style={{ fontSize: 13 }}>{t('trips.timeline')}</h3>
              <ol data-testid="trips-timeline" style={{ paddingLeft: 18 }}>
                {detail.timeline.map((e) => (
                  <li key={e.seq} data-testid={`trips-event-${e.seq}`}>
                    <span className="mono">{e.type}</span> <span className="muted">{e.occurredAt}</span>
                  </li>
                ))}
                {detail.timeline.length === 0 && <li className="muted">{t('trips.noEvents')}</li>}
              </ol>
              <button onClick={() => setDetail(null)} data-testid="trips-detail-close">
                {t('trips.close')}
              </button>
              {canCancel && NON_TERMINAL.has(detail.ride.status) && (
                <button
                  onClick={() => void cancelRide(detail.ride.id)}
                  disabled={actionBusyId === detail.ride.id}
                  style={{ marginLeft: 8 }}
                  data-testid="trips-detail-cancel"
                >
                  {t('trips.cancel')}
                </button>
              )}
              {canRefund && detail.ride.status === 'COMPLETED' && detail.ride.paymentMethod === 'WALLET' && (
                <button
                  onClick={() => void refundRide(detail.ride.id)}
                  disabled={actionBusyId === detail.ride.id}
                  style={{ marginLeft: 8 }}
                  data-testid="trips-detail-refund"
                >
                  {t('trips.refund')}
                </button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

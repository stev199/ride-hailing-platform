import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Fares editor (Phase 6 "Money: fares per city and ride type", delivery plan item 6):
 * GET /v1/admin/config/fares (CONFIG_READ) renders the live fares document as a
 * per-city x per-ride-type grid; CONFIG_WRITE opens the editor. The backend PATCH is
 * a D75 FULL-REPLACE - the body must carry the WHOLE fares document (every schema
 * key at every level; a partial body is a 400, never a silent default reset) - so
 * the editor clones the loaded document, edits numeric fields in place and sends
 * the complete document back on Save. Server errors surface verbatim (the backend
 * is the single authority on the fares contract: negative amounts, empty cities,
 * currency mismatches are rejected there).
 */

/** Wire shape of the fares document (mirrors backend FareConfig.kt). */
export interface SurgeWindow {
  multiplier: number;
  startHourUtc: number;
  endHourUtc: number;
}

export interface RideTypeFares {
  base: number;
  perKm: number;
  perMinute: number;
  minimumFare: number;
  bookingFee: number;
  cancelFee: number;
  waitFeePerMinute: number;
  night: SurgeWindow;
  peak: SurgeWindow;
}

export interface CityFares {
  currency: string;
  rideTypes: Record<string, RideTypeFares>;
}

export interface FareConfig {
  cities: Record<string, CityFares>;
  straightLine: { detourFactor: number; assumedAvgSpeedKmh: number };
  quote: { validitySeconds: number; mode: string; meteredCapMultiplier: number };
}

/** Numeric fields an admin edits per ride type (fee set, spec §69 field names). */
const RIDE_TYPE_NUMERIC_FIELDS: Array<{ key: keyof RideTypeFares; labelKey: string }> = [
  { key: 'base', labelKey: 'fares.base' },
  { key: 'perKm', labelKey: 'fares.perKm' },
  { key: 'perMinute', labelKey: 'fares.perMinute' },
  { key: 'minimumFare', labelKey: 'fares.minimumFare' },
  { key: 'bookingFee', labelKey: 'fares.bookingFee' },
  { key: 'cancelFee', labelKey: 'fares.cancelFee' },
  { key: 'waitFeePerMinute', labelKey: 'fares.waitFeePerMinute' },
];

const SURGE_FIELDS: Array<{ key: keyof SurgeWindow; labelKey: string }> = [
  { key: 'multiplier', labelKey: 'fares.surgeMultiplier' },
  { key: 'startHourUtc', labelKey: 'fares.startHourUtc' },
  { key: 'endHourUtc', labelKey: 'fares.endHourUtc' },
];

/** Deep clone via JSON - the document is plain JSON by construction. */
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

/** Read a numeric leaf by dotted path on a plain-JSON object (null when absent/non-numeric). */
function readNumber(root: unknown, path: string): number | null {
  let node: unknown = root;
  for (const key of path.split('.')) {
    if (node && typeof node === 'object') node = (node as Record<string, unknown>)[key];
    else return null;
  }
  return typeof node === 'number' ? node : null;
}

/** Set a numeric leaf by dotted path on a plain-JSON object. */
function setByPath(root: unknown, path: string, value: number): void {
  const keys = path.split('.');
  let node = root as Record<string, unknown>;
  for (let i = 0; i < keys.length - 1; i++) node = node[keys[i]] as Record<string, unknown>;
  node[keys[keys.length - 1]] = value;
}

export default function FaresPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [doc, setDoc] = useState<FareConfig | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const canRead = !!me && can(me.role, Perm.CONFIG_READ);
  const canWrite = !!me && can(me.role, Perm.CONFIG_WRITE);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      setDoc(await api.get<FareConfig>('/v1/admin/config/fares'));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }, [api, canRead]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="CONFIG_READ" />;

  const cities = doc ? Object.entries(doc.cities) : [];

  return (
    <div>
      <h1 className="page-title">{t('fares.title')}</h1>
      {notice && (
        <div className="ok-banner" role="status" data-testid="fares-notice">
          {notice}
        </div>
      )}
      {error && (
        <div className="error-banner" data-testid="fares-error">
          {error.code}: {error.message}
        </div>
      )}
      {doc && (
        <>
          {canWrite && !editing && (
            <div className="search-row">
              <button
                className="primary"
                onClick={() => {
                  setError(null);
                  setEditing(true);
                }}
                data-testid="fares-edit"
              >
                {t('fares.edit')}
              </button>
            </div>
          )}
          {editing && doc && (
            <FaresEditPanel
              doc={doc}
              api={api}
              onClose={() => setEditing(false)}
              onDone={async (msg) => {
                setEditing(false);
                setError(null);
                setNotice(msg);
                await load();
              }}
            />
          )}
          {cities.map(([city, cityFares]) => (
            <div className="card" key={city} data-testid={`fares-city-${city}`}>
              <h2 style={{ marginTop: 0, fontSize: 14 }}>
                {city} · {cityFares.currency}
              </h2>
              <table className="data" aria-label={`${city} fares`} data-testid={`fares-table-${city}`}>
                <thead>
                  <tr>
                    <th>{t('fares.rideType')}</th>
                    {RIDE_TYPE_NUMERIC_FIELDS.map((f) => (
                      <th key={f.key}>{t(f.labelKey)}</th>
                    ))}
                    <th>{t('fares.night')}</th>
                    <th>{t('fares.peak')}</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(cityFares.rideTypes).map(([type, fares]) => (
                    <tr key={type} data-testid={`fares-row-${city}-${type}`}>
                      <td>{type}</td>
                      {RIDE_TYPE_NUMERIC_FIELDS.map((f) => (
                        <td key={f.key}>{String(fares[f.key])}</td>
                      ))}
                      <td>
                        ×{fares.night.multiplier} ({fares.night.startHourUtc}–{fares.night.endHourUtc} UTC)
                      </td>
                      <td>
                        ×{fares.peak.multiplier} ({fares.peak.startHourUtc}–{fares.peak.endHourUtc} UTC)
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
          <div className="card" data-testid="fares-policy">
            <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('fares.policyTitle')}</h2>
            <p className="muted" data-testid="fares-quote-policy">
              {t('fares.quoteMode')}: {doc.quote.mode} · {t('fares.validitySeconds')}: {doc.quote.validitySeconds} ·{' '}
              {t('fares.meteredCap')}: ×{doc.quote.meteredCapMultiplier}
            </p>
            <p className="muted" data-testid="fares-straight-line">
              {t('fares.straightLine')}: {t('fares.detourFactor')} ×{doc.straightLine.detourFactor} ·{' '}
              {t('fares.assumedSpeed')}: {doc.straightLine.assumedAvgSpeedKmh} km/h
            </p>
          </div>
        </>
      )}
      {!doc && !error && busy && <div className="info-banner">{t('fares.loading')}</div>}
    </div>
  );
}

/**
 * Editor panel: a deep clone of the live document with raw-string numeric inputs
 * (invalid input stays local and blocks Save with a client-side message; the server
 * still owns the final contract). Save = FULL-REPLACE PATCH (D75): the whole document.
 */
function FaresEditPanel({
  doc,
  api,
  onClose,
  onDone,
}: {
  doc: FareConfig;
  api: { patch: <T>(p: string, b?: unknown) => Promise<T> };
  onClose: () => void;
  onDone: (notice: string) => void | Promise<void>;
}) {
  const { t } = useTranslation();
  const [state, setState] = useState<FareConfig>(() => clone(doc));
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  /** Raw strings per numeric leaf, keyed by path; absent = unchanged from doc. */
  const [raw, setRaw] = useState<Record<string, string>>({});

  const setRawFor = (path: string, value: string) => setRaw((r) => ({ ...r, [path]: value }));

  /**
   * Apply every edited raw value onto a CLONE of the document. Returns the first
   * invalid path (non-finite, negative where forbidden, or a path that is not a
   * numeric leaf) as a string, or the VALIDATED document to send. The validated
   * document - not the state closure - is what submit() puts on the wire (setState
   * is async; the test for the full-replace body caught exactly that).
   */
  const applyAndValidate = (): FareConfig | string => {
    const next = clone(state);
    for (const [path, rawValue] of Object.entries(raw)) {
      const n = Number(rawValue);
      if (!Number.isFinite(n)) return path;
      if (!path.includes('HourUtc') && n < 0) return path;
      if (readNumber(state, path) === null) return path;
      setByPath(next, path, n);
    }
    setState(next);
    return next;
  };

  const submit = async () => {
    const result = applyAndValidate();
    if (typeof result === 'string') {
      setError({ code: 'invalid_input', message: `${result}: ${t('fares.invalidNumber')}`, traceId: null });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // D75 full-replace: the WHOLE document goes on the wire.
      await api.patch('/v1/admin/config/fares', result);
      await onDone(t('fares.saved'));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  };

  const cities = useMemo(() => Object.entries(state.cities), [state]);

  return (
    <div className="card" role="dialog" aria-modal="true" aria-label={t('fares.edit')} data-testid="fares-edit-panel">
      <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('fares.edit')}</h2>
      {error && (
        <div className="error-banner" role="alert" data-testid="fares-edit-error">
          {error.code}: {error.message}
        </div>
      )}
      <p className="muted">{t('fares.fullReplaceNote')}</p>
      {cities.map(([city, cityFares]) => (
        <div key={city} data-testid={`fares-edit-${city}`}>
          <h3 style={{ fontSize: 13 }}>
            {city} · {cityFares.currency}
          </h3>
          {Object.entries(cityFares.rideTypes).map(([type, fares]) => (
            <div key={type} data-testid={`fares-edit-${city}-${type}`}>
              <h4 style={{ fontSize: 12, margin: '4px 0' }}>{type}</h4>
              <div className="form-grid">
                {RIDE_TYPE_NUMERIC_FIELDS.map((f) => {
                  const path = `cities.${city}.rideTypes.${type}.${f.key}`;
                  return (
                    <div key={path} style={{ display: 'contents' }}>
                      <label htmlFor={`in-${path}`}>{t(f.labelKey)}</label>
                      <input
                        id={`in-${path}`}
                        type="number"
                        step="0.01"
                        style={{ width: 90 }}
                        value={raw[path] ?? String(fares[f.key])}
                        aria-label={`${city} ${type} ${f.key}`}
                        onChange={(e) => setRawFor(path, e.target.value)}
                        data-testid={`in-${path}`}
                      />
                    </div>
                  );
                })}
                {(['night', 'peak'] as const).map((win) =>
                  SURGE_FIELDS.map((f) => {
                    const path = `cities.${city}.rideTypes.${type}.${win}.${f.key}`;
                    return (
                      <div key={path} style={{ display: 'contents' }}>
                        <label htmlFor={`in-${path}`}>
                          {win} · {t(f.labelKey)}
                        </label>
                        <input
                          id={`in-${path}`}
                          type="number"
                          step="0.01"
                          style={{ width: 90 }}
                          value={raw[path] ?? String(fares[win][f.key])}
                          aria-label={`${city} ${type} ${win} ${f.key}`}
                          onChange={(e) => setRawFor(path, e.target.value)}
                          data-testid={`in-${path}`}
                        />
                      </div>
                    );
                  }),
                )}
              </div>
            </div>
          ))}
        </div>
      ))}
      <div className="form-grid">
        {(
          [
            ['straightLine.detourFactor', state.straightLine.detourFactor],
            ['straightLine.assumedAvgSpeedKmh', state.straightLine.assumedAvgSpeedKmh],
            ['quote.validitySeconds', state.quote.validitySeconds],
            ['quote.meteredCapMultiplier', state.quote.meteredCapMultiplier],
          ] as Array<[string, number]>
        ).map(([path, value]) => (
          <div key={path} style={{ display: 'contents' }}>
            <label htmlFor={`in-${path}`}>{path}</label>
            <input
              id={`in-${path}`}
              type="number"
              step="0.01"
              style={{ width: 90 }}
              value={raw[path] ?? String(value)}
              aria-label={path}
              onChange={(e) => setRawFor(path, e.target.value)}
              data-testid={`in-${path}`}
            />
          </div>
        ))}
      </div>
      <div className="search-row">
        <button className="primary" onClick={() => void submit()} disabled={busy} data-testid="fares-edit-submit">
          {t('fares.save')}
        </button>
        <button onClick={onClose} data-testid="fares-edit-cancel">
          {t('fares.cancel')}
        </button>
      </div>
    </div>
  );
}

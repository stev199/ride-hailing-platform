import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * P7-6 promos editor (D75 FULL-REPLACE like FaresPage): GET /v1/admin/config/promos
 * (CONFIG_READ) renders the live codes; CONFIG_WRITE edits rows in place and sends
 * the WHOLE document back on Save (a partial body is a 400, never a silent reset).
 * The backend validates completely (code shape, kind bounds, window order, per-user
 * limit, duplicate codes, 50-code cap) and bumps the signed bundle version. Server
 * errors surface VERBATIM.
 */

/** Wire shape of one promo code (mirrors backend PromoCodeConfig.kt). */
export interface PromoCodeRow {
  code: string;
  kind: string;
  value: number;
  currency: string | null;
  validFromEpochSec: number;
  validUntilEpochSec: number;
  perUserLimit: number;
  active: boolean;
}

export interface PromoConfigDoc {
  codes: PromoCodeRow[];
}

export interface PromoConfigUpdated {
  version: number;
  config: PromoConfigDoc;
}

const emptyRow = (): PromoCodeRow => ({
  code: '',
  kind: 'PERCENT',
  value: 10,
  currency: null,
  validFromEpochSec: 0,
  validUntilEpochSec: Math.floor(Date.now() / 1000) + 30 * 24 * 3600,
  perUserLimit: 1,
  active: true,
});

export default function PromosPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [doc, setDoc] = useState<PromoConfigDoc | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [savedVersion, setSavedVersion] = useState<number | null>(null);

  const canRead = !!me && can(me.role, Perm.CONFIG_READ);
  const canWrite = !!me && can(me.role, Perm.CONFIG_WRITE);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      setDoc(await api.get<PromoConfigDoc>('/v1/admin/config/promos'));
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

  const editRow = (i: number, patch: Partial<PromoCodeRow>) => {
    setDoc((d) =>
      d ? { ...d, codes: d.codes.map((c, idx) => (idx === i ? { ...c, ...patch } : c)) } : d,
    );
  };

  const addRow = () => setDoc((d) => (d ? { ...d, codes: [...d.codes, emptyRow()] } : d));

  const removeRow = (i: number) =>
    setDoc((d) => (d ? { ...d, codes: d.codes.filter((_, idx) => idx !== i) } : d));

  const save = async () => {
    if (!doc) return;
    setBusy(true);
    setError(null);
    setSavedVersion(null);
    try {
      const res = await api.patch<PromoConfigUpdated>('/v1/admin/config/promos', {
        codes: doc.codes,
      });
      setSavedVersion(res.version);
      setDoc(res.config);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h1 className="page-title">{t('promos.title')}</h1>
      {error && (
        <div className="error-banner" data-testid="promos-error">
          {error.code}: {error.message}
        </div>
      )}
      {savedVersion !== null && (
        <div className="card" role="status" data-testid="promos-saved">
          {t('promos.saved', { version: String(savedVersion) })}
        </div>
      )}
      {busy && <span className="muted">{t('promos.loading')}</span>}
      {doc && (
        <table className="data" aria-label={t('promos.title')} data-testid="promos-table">
          <thead>
            <tr>
              <th>{t('promos.code')}</th>
              <th>{t('promos.kind')}</th>
              <th>{t('promos.value')}</th>
              <th>{t('promos.currency')}</th>
              <th>{t('promos.validFrom')}</th>
              <th>{t('promos.validUntil')}</th>
              <th>{t('promos.perUserLimit')}</th>
              <th>{t('promos.active')}</th>
              {canWrite && <th>{t('promos.remove')}</th>}
            </tr>
          </thead>
          <tbody>
            {doc.codes.map((c, i) => (
              <tr key={i} data-testid={`promos-row-${i}`}>
                <td>
                  <input
                    type="text"
                    aria-label={t('promos.code')}
                    value={c.code}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { code: e.target.value.toUpperCase() })}
                    data-testid={`promos-code-${i}`}
                  />
                </td>
                <td>
                  <select
                    aria-label={t('promos.kind')}
                    value={c.kind}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { kind: e.target.value })}
                    data-testid={`promos-kind-${i}`}
                  >
                    <option value="PERCENT">PERCENT</option>
                    <option value="AMOUNT">AMOUNT</option>
                  </select>
                </td>
                <td>
                  <input
                    type="number"
                    aria-label={t('promos.value')}
                    value={c.value}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { value: Number(e.target.value) })}
                    data-testid={`promos-value-${i}`}
                  />
                </td>
                <td>
                  <input
                    type="text"
                    aria-label={t('promos.currency')}
                    value={c.currency ?? ''}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { currency: e.target.value || null })}
                    data-testid={`promos-currency-${i}`}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    aria-label={t('promos.validFrom')}
                    value={c.validFromEpochSec}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { validFromEpochSec: Number(e.target.value) })}
                    data-testid={`promos-from-${i}`}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    aria-label={t('promos.validUntil')}
                    value={c.validUntilEpochSec}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { validUntilEpochSec: Number(e.target.value) })}
                    data-testid={`promos-until-${i}`}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    aria-label={t('promos.perUserLimit')}
                    value={c.perUserLimit}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { perUserLimit: Number(e.target.value) })}
                    data-testid={`promos-limit-${i}`}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    aria-label={t('promos.active')}
                    checked={c.active}
                    disabled={!canWrite}
                    onChange={(e) => editRow(i, { active: e.target.checked })}
                    data-testid={`promos-active-${i}`}
                  />
                </td>
                {canWrite && (
                  <td>
                    <button
                      onClick={() => removeRow(i)}
                      disabled={!canWrite}
                      data-testid={`promos-remove-${i}`}
                    >
                      {t('promos.remove')}
                    </button>
                  </td>
                )}
              </tr>
            ))}
            {doc.codes.length === 0 && (
              <tr>
                <td colSpan={9} className="muted" data-testid="promos-empty">
                  {t('promos.empty')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}
      {canWrite && (
        <div className="search-row">
          <button onClick={addRow} data-testid="promos-add">
            {t('promos.add')}
          </button>
          <button onClick={() => void save()} disabled={busy || !doc} data-testid="promos-save">
            {t('promos.save')}
          </button>
        </div>
      )}
    </div>
  );
}

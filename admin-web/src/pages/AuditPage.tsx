import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError, AuditTail } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/** Audit tail viewer: GET /v1/admin/audit?limit (AUDIT_READ); verifyOk = whole-chain recompute. */
export default function AuditPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [tail, setTail] = useState<AuditTail | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const canRead = !!me && can(me.role, Perm.AUDIT_READ);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      setTail(await api.get<AuditTail>('/v1/admin/audit?limit=50'));
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
  if (!canRead) return <ForbiddenPerm perm="AUDIT_READ" />;

  return (
    <div>
      <h1 className="page-title">{t('audit.title', { count: tail?.events.length ?? 0 })}</h1>
      {tail && (
        <div className={tail.verifyOk ? 'ok-banner' : 'error-banner'} data-testid="verify-banner">
          {tail.verifyOk ? t('audit.chainOk') : t('audit.chainBroken')}
        </div>
      )}
      {error && <div className="error-banner" role="alert">{error.code}: {error.message}</div>}
      {!tail && !error && <p className="muted">{busy ? t('audit.loading') : ''}</p>}
      {tail && tail.events.length === 0 && <div className="info-banner">{t('audit.empty')}</div>}
      {tail && tail.events.length > 0 && (
        <table className="data" aria-label={t('audit.tableAria')} data-testid="audit-table">
          <thead>
            <tr>
              <th>{t('audit.seq')}</th>
              <th>{t('audit.type')}</th>
              <th>{t('audit.occurredAt')}</th>
              <th>{t('audit.payload')}</th>
              <th>{t('audit.hash')}</th>
            </tr>
          </thead>
          <tbody>
            {tail.events.map((ev) => (
              <tr key={ev.seq} data-testid="audit-row">
                <td className="mono">{ev.seq}</td>
                <td>{ev.type}</td>
                <td className="mono">{ev.occurredAt}</td>
                <td className="mono" style={{ maxWidth: 320, overflowWrap: 'anywhere' }}>{ev.payloadJson}</td>
                <td><code className="hash">{ev.hash.slice(0, 12)}…</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

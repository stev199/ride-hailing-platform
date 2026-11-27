import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Verification center (Phase 6 "Verification: review driver docs, approve/reject,
 * re-review", P6-1): GET /v1/admin/verification (VERIFICATION_READ) with a status
 * filter; VERIFICATION_WRITE holders approve or reject (reject REQUIRES a reason -
 * the driver sees it verbatim on GET /v1/verification/me). APPROVED is terminal
 * (revocation is an admin ban); a REJECTED driver may resubmit and re-enter the
 * queue. V1 shows the DECLARED document names - image upload + encrypted storage
 * is the S-03 slice and is not pretended here.
 */

export interface VerificationSubmission {
  id: string;
  userId: string;
  licenseNumber: string;
  vehiclePlate: string;
  declaredDocs: string[];
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  submittedAtEpochSec: number;
  reviewedAtEpochSec: number | null;
  reviewerId: string | null;
  rejectReason: string | null;
}

export interface VerificationListResponse {
  submissions: VerificationSubmission[];
  limit: number;
  offset: number;
}

const STATUS_FILTERS = ['PENDING', 'APPROVED', 'REJECTED'] as const;

const PAGE_SIZE = 25;

export default function VerificationPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [rows, setRows] = useState<VerificationSubmission[]>([]);
  const [status, setStatus] = useState<string>('PENDING');
  const [offset, setOffset] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadedOnce, setLoadedOnce] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  /** Reject flow: which row is rejecting + the reviewer reason. */
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const canRead = !!me && can(me.role, Perm.VERIFICATION_READ);
  const canWrite = !!me && can(me.role, Perm.VERIFICATION_WRITE);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      const q = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (status) q.set('status', status);
      const res = await api.get<VerificationListResponse>(`/v1/admin/verification?${q.toString()}`);
      setRows(res.submissions);
      setLoadedOnce(true);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }, [api, canRead, offset, status]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="VERIFICATION_READ" />;

  const review = async (id: string, kind: 'approve' | 'reject') => {
    setBusyId(id);
    setActionError(null);
    try {
      // The server answer IS the new state (no optimistic update to lie about).
      const updated = await api.post<VerificationSubmission>(
        `/v1/admin/verification/${id}/${kind}`,
        kind === 'reject' ? { reason: reason.trim() } : {},
      );
      setRows((prev) => prev.filter((r) => r.id !== id).concat(updated));
      if (kind === 'reject') {
        setRejecting(null);
        setReason('');
      }
    } catch (err) {
      const apiErr = err as ApiError;
      setActionError(`${apiErr.code}: ${apiErr.message}`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <h1 className="page-title">{t('verification.title')}</h1>
      {(error || actionError) && (
        <div className="error-banner" data-testid="verification-error">
          {error ? `${error.code}: ${error.message}` : actionError}
        </div>
      )}
      <div className="search-row">
        <select
          aria-label={t('verification.statusFilter')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setOffset(0);
          }}
          data-testid="verification-status-filter"
        >
          <option value="">{t('verification.allStatuses')}</option>
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        {busy && <span className="muted">{t('verification.loading')}</span>}
      </div>
      {loadedOnce && (
        <table className="data" aria-label={t('verification.title')} data-testid="verification-table">
          <thead>
            <tr>
              <th>{t('verification.driver')}</th>
              <th>{t('verification.license')}</th>
              <th>{t('verification.plate')}</th>
              <th>{t('verification.docs')}</th>
              <th>{t('verification.status')}</th>
              {canWrite && <th>{t('verification.actions')}</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.id} data-testid={`verification-row-${s.id}`}>
                <td className="mono">{s.userId.slice(0, 8)}…</td>
                <td className="mono">{s.licenseNumber}</td>
                <td className="mono">{s.vehiclePlate}</td>
                <td>
                  {s.declaredDocs.map((d) => (
                    <span key={d} className="badge" style={{ marginRight: 4 }}>
                      {d}
                    </span>
                  ))}
                </td>
                <td data-testid={`verification-status-${s.id}`}>
                  <span className="badge">{s.status}</span>
                  {s.rejectReason && (
                    <span className="muted" data-testid={`verification-reason-${s.id}`}>
                      {' '}
                      · {s.rejectReason}
                    </span>
                  )}
                </td>
                {canWrite && (
                  <td>
                    {s.status === 'PENDING' ? (
                      <>
                        <button
                          onClick={() => void review(s.id, 'approve')}
                          disabled={busyId !== null}
                          data-testid={`verification-approve-${s.id}`}
                        >
                          {t('verification.approve')}
                        </button>{' '}
                        <button
                          onClick={() => {
                            setRejecting(s.id);
                            setReason('');
                            setActionError(null);
                          }}
                          disabled={busyId !== null}
                          data-testid={`verification-reject-open-${s.id}`}
                        >
                          {t('verification.reject')}
                        </button>
                        {rejecting === s.id && (
                          <span style={{ marginLeft: 8 }}>
                            <input
                              type="text"
                              aria-label={t('verification.reasonLabel')}
                              placeholder={t('verification.reasonPlaceholder')}
                              value={reason}
                              onChange={(e) => setReason(e.target.value)}
                              data-testid={`verification-reason-input-${s.id}`}
                            />{' '}
                            <button
                              onClick={() => void review(s.id, 'reject')}
                              disabled={busyId !== null || reason.trim().length === 0}
                              data-testid={`verification-reject-confirm-${s.id}`}
                            >
                              {t('verification.rejectConfirm')}
                            </button>
                          </span>
                        )}
                      </>
                    ) : (
                      <span className="muted">{t('verification.reviewed')}</span>
                    )}
                  </td>
                )}
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={canWrite ? 6 : 5} className="muted" data-testid="verification-empty">
                  {t('verification.empty')}
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
          data-testid="verification-prev"
        >
          {t('verification.prev')}
        </button>
        <span className="muted" data-testid="verification-offset">
          {offset}
        </span>
        <button
          onClick={() => setOffset(offset + PAGE_SIZE)}
          disabled={rows.length < PAGE_SIZE}
          data-testid="verification-next"
        >
          {t('verification.next')}
        </button>
      </div>
    </div>
  );
}

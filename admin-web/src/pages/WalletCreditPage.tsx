import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { AdminUser, AdminUserList, ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * P7-5b wallet admin page (MONEY_READ to view, MONEY_WRITE to act):
 * - Find a user by phone (GET /v1/admin/users?query=...)
 * - Read their POINTS balance (GET /v1/wallet is the OWNER surface - admin reads go
 *   through the same owner endpoint only for the SIGNED-IN user, so v1 shows the
 *   credit RESPONSE balances instead of a standalone balance read; honest note).
 * - Issue an admin credit (POST /v1/admin/users/{id}/wallet-credit, MONEY_WRITE,
 *   reason required, server-side caps enforced).
 * - Payouts v1: NO payout API exists yet - the payout queue is provider work; the
 *   honest note stays on screen until it lands (P7-4 driver note mirrors this).
 */
export default function WalletCreditPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [searching, setSearching] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [amount, setAmount] = useState<Record<string, string>>({});
  const [reason, setReason] = useState<Record<string, string>>({});

  const canRead = !!me && can(me.role, Perm.MONEY_READ);
  const canWrite = !!me && can(me.role, Perm.MONEY_WRITE);

  const search = useCallback(
    async (q: string) => {
      if (!canRead) return;
      setSearching(true);
      setError(null);
      setNotice(null);
      try {
        const params = new URLSearchParams({ limit: '10', offset: '0' });
        if (q.trim()) params.set('query', q.trim());
        const res = await api.get<AdminUserList>(`/v1/admin/users?${params.toString()}`);
        setUsers(res.users);
      } catch (err) {
        setError(err as ApiError);
      } finally {
        setSearching(false);
      }
    },
    [api, canRead],
  );

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="MONEY_READ" />;

  const credit = async (u: AdminUser) => {
    const minor = Number(amount[u.id] ?? '');
    const why = (reason[u.id] ?? '').trim();
    if (!Number.isFinite(minor) || minor <= 0) {
      setError({ code: 'invalid_amount', message: t('wallet.invalidAmount'), traceId: null });
      return;
    }
    if (!why) {
      setError({ code: 'invalid_reason', message: t('wallet.invalidReason'), traceId: null });
      return;
    }
    setBusyId(u.id);
    setError(null);
    setNotice(null);
    try {
      const res = await api.post<{ balances: Record<string, number> }>(
        `/v1/admin/users/${u.id}/wallet-credit`,
        { amountMinor: minor, reason: why },
      );
      setNotice(t('wallet.credited', { user: u.displayName, points: String(res.balances['POINTS'] ?? 0) }));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <h1 className="page-title">{t('wallet.title')}</h1>
      {error && (
        <div className="error-banner" data-testid="wallet-error">
          {error.code}: {error.message}
        </div>
      )}
      {notice && (
        <div className="card" role="status" data-testid="wallet-notice">
          {notice}
        </div>
      )}
      <div className="search-row">
        <input
          type="search"
          aria-label={t('wallet.searchAria')}
          placeholder={t('wallet.searchPlaceholder')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void search(query);
          }}
          data-testid="wallet-search"
        />
        <button onClick={() => void search(query)} data-testid="wallet-search-go">
          {t('wallet.search')}
        </button>
        {searching && <span className="muted">{t('wallet.searching')}</span>}
      </div>

      {users.length > 0 && (
        <table className="data" aria-label={t('wallet.title')} data-testid="wallet-users">
          <thead>
            <tr>
              <th>{t('wallet.user')}</th>
              <th>{t('wallet.phone')}</th>
              <th>{t('wallet.role')}</th>
              {canWrite && <th>{t('wallet.amountLabel')}</th>}
              {canWrite && <th>{t('wallet.reasonLabel')}</th>}
              {canWrite && <th>{t('wallet.actions')}</th>}
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} data-testid={`wallet-user-${u.id}`}>
                <td>
                  {u.displayName} <span className="mono muted">{u.id.slice(0, 8)}…</span>
                </td>
                <td>{u.phone ?? '—'}</td>
                <td>{u.role}</td>
                {canWrite && (
                  <>
                    <td>
                      <input
                        type="number"
                        min={1}
                        aria-label={t('wallet.amountLabel')}
                        value={amount[u.id] ?? ''}
                        onChange={(e) => setAmount({ ...amount, [u.id]: e.target.value })}
                        data-testid={`wallet-amount-${u.id}`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        aria-label={t('wallet.reasonLabel')}
                        value={reason[u.id] ?? ''}
                        onChange={(e) => setReason({ ...reason, [u.id]: e.target.value })}
                        data-testid={`wallet-reason-${u.id}`}
                      />
                    </td>
                    <td>
                      <button
                        onClick={() => void credit(u)}
                        disabled={busyId === u.id}
                        data-testid={`wallet-credit-${u.id}`}
                      >
                        {t('wallet.credit')}
                      </button>
                    </td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="muted" data-testid="wallet-payout-note">
        {t('wallet.payoutNote')}
      </p>
    </div>
  );
}

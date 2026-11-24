import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import type { AdminUser, AdminUserList, ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';

/**
 * Users list/search (P1-4 read-only v0): GET /v1/admin/users?query&limit&offset
 * (USERS_READ). Mutations (create/edit/ban/reset/force-logout) are Phase-1 later
 * tasks - buttons render disabled with a hint until their services land.
 */
export default function UsersPage() {
  const { user: me, api } = useAuth();
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const canRead = !!me && can(me.role, Perm.USERS_READ);

  const load = useCallback(
    async (q: string) => {
      if (!canRead) return;
      setBusy(true);
      setError(null);
      try {
        const params = new URLSearchParams({ limit: '20', offset: '0' });
        if (q.trim()) params.set('query', q.trim());
        const res = await api.get<AdminUserList>(`/v1/admin/users?${params.toString()}`);
        setUsers(res.users);
      } catch (err) {
        setError(err as ApiError);
        setUsers(null);
      } finally {
        setBusy(false);
      }
    },
    [api, canRead],
  );

  useEffect(() => {
    void load('');
  }, [load]);

  if (!me) return null;
  if (!canRead) return <ForbiddenPerm perm="USERS_READ" />;

  return (
    <div>
      <h1 className="page-title">Users</h1>
      <div className="search-row">
        <input
          type="search"
          aria-label="Search users"
          placeholder="Search by phone, email or display name"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void load(query);
          }}
          data-testid="user-search"
        />
        <button className="primary" onClick={() => void load(query)} disabled={busy} data-testid="user-search-go">
          Search
        </button>
      </div>
      {error && <div className="error-banner" role="alert">{error.code}: {error.message}</div>}
      {!users && !error && <p className="muted">Loading…</p>}
      {users && users.length === 0 && <div className="info-banner">No users match this query.</div>}
      {users && users.length > 0 && (
        <table className="data" aria-label="Users list" data-testid="users-table">
          <thead>
            <tr>
              <th>Display name</th>
              <th>Phone</th>
              <th>Email</th>
              <th>Role</th>
              <th>Status</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} data-testid="user-row">
                <td>{u.displayName}</td>
                <td className="mono">{u.phone ?? '—'}</td>
                <td>{u.email ?? '—'}</td>
                <td><span className="badge role">{u.role}</span></td>
                <td>{u.status}</td>
                <td className="mono">{new Date(u.createdAtEpochSec * 1000).toISOString().slice(0, 10)}</td>
                <td>
                  <span className="muted" title="Mutations land with P1-4b (users admin actions UI)">
                    {[
                      Perm.USERS_WRITE,
                      Perm.USERS_BAN,
                      Perm.USERS_RESET_PASSWORD,
                      Perm.SESSIONS_ADMIN,
                    ].some((p) => can(me.role, p))
                      ? 'edit UI in P1-4b'
                      : 'read-only role'}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export function ForbiddenPerm({ perm }: { perm: string }) {
  return (
    <div className="error-banner" role="alert" data-testid="forbidden-perm">
      Missing permission <strong>{perm}</strong> — this screen requires a role that carries it.
    </div>
  );
}

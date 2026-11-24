import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import type { AdminUser, AdminUserList, ApiError, User } from '../api/types';
import { can, Perm } from '../rbac/permissions';

/**
 * Users admin (P1-4 read list + P1-4b mutations): GET /v1/admin/users?query&limit&offset
 * (USERS_READ); create (USERS_WRITE), edit/ban (USERS_WRITE), reset password
 * (USERS_RESET_PASSWORD), force logout (SESSIONS_ADMIN). Every mutation is
 * server-enforced (RBAC + self-protection + super-only admin roles, D62) - the UI
 * only hides what the server would 403 anyway and surfaces server errors verbatim.
 */

const ROLES = ['RIDER', 'DRIVER', 'ADMIN_SUPPORT', 'ADMIN_OPS', 'ADMIN_VERIFIER', 'ADMIN_FINANCE', 'ADMIN_READONLY', 'ADMIN_SUPER'] as const;
const STATUSES = ['ACTIVE', 'BANNED_TEMP', 'BANNED_PERM', 'DELETED'] as const;

type ActionKind = 'create' | 'edit' | 'reset-password' | 'force-logout';

interface ActionState {
  kind: ActionKind;
  target: AdminUser | null;
  /** Ban opens the edit panel with the status preset. */
  presetStatus?: string;
}

const ACTION_LABEL: Record<ActionKind, string> = {
  create: 'Create user',
  edit: 'Edit user',
  'reset-password': 'Reset password',
  'force-logout': 'Force logout',
};

export default function UsersPage() {
  const { user: me, api } = useAuth();
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [action, setAction] = useState<ActionState | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const canRead = !!me && can(me.role, Perm.USERS_READ);
  const canWrite = !!me && can(me.role, Perm.USERS_WRITE);
  const canBan = !!me && can(me.role, Perm.USERS_BAN);
  const canReset = !!me && can(me.role, Perm.USERS_RESET_PASSWORD);
  const canSessions = !!me && can(me.role, Perm.SESSIONS_ADMIN);

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

  const closePanel = () => {
    setAction(null);
    setError(null);
  };

  return (
    <div>
      <h1 className="page-title">Users</h1>
      {notice && (
        <div className="ok-banner" role="status" data-testid="user-action-notice">
          {notice}
        </div>
      )}
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
        {canWrite && (
          <button
            className="primary"
            onClick={() => setAction({ kind: 'create', target: null })}
            data-testid="user-create"
          >
            Create user
          </button>
        )}
      </div>
      {action && (
        <UserActionPanel
          action={action}
          me={me}
          api={api}
          canBan={canBan}
          onClose={closePanel}
          onDone={async (msg) => {
            setAction(null);
            setError(null);
            setNotice(msg);
            await load(query);
          }}
        />
      )}
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
                  <div className="row-actions">
                    {canWrite && (
                      <button
                        onClick={() => setAction({ kind: 'edit', target: u })}
                        data-testid={`user-edit-${u.id}`}
                      >
                        Edit
                      </button>
                    )}
                    {canBan && u.status === 'ACTIVE' && (
                      <button
                        onClick={() => setAction({ kind: 'edit', target: u, presetStatus: 'BANNED_TEMP' })}
                        data-testid={`user-ban-${u.id}`}
                      >
                        Ban
                      </button>
                    )}
                    {canReset && (
                      <button
                        onClick={() => setAction({ kind: 'reset-password', target: u })}
                        data-testid={`user-reset-${u.id}`}
                      >
                        Reset pw
                      </button>
                    )}
                    {canSessions && (
                      <button
                        onClick={() => setAction({ kind: 'force-logout', target: u })}
                        data-testid={`user-logout-${u.id}`}
                      >
                        Force logout
                      </button>
                    )}
                    {!canWrite && !canBan && !canReset && !canSessions && <span className="muted">read-only role</span>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** Per-action panel. Server errors are shown verbatim (the server is the authority). */
function UserActionPanel({
  action,
  me,
  api,
  canBan,
  onClose,
  onDone,
}: {
  action: ActionState;
  me: Pick<User, 'id'>;
  api: { post: <T>(p: string, b?: unknown) => Promise<T>; patch: <T>(p: string, b?: unknown) => Promise<T> };
  canBan: boolean;
  onClose: () => void;
  onDone: (notice: string) => void | Promise<void>;
}) {
  const { kind, target } = action;
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [displayName, setDisplayName] = useState(target?.displayName ?? '');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [locale, setLocale] = useState(target?.locale ?? 'en');
  const [role, setRole] = useState<string>(target?.role ?? 'RIDER');
  const [status, setStatus] = useState<string>(action.presetStatus ?? target?.status ?? 'ACTIVE');

  const run = async (fn: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    try {
      const notice = await fn();
      await onDone(notice);
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  };

  const submit = () => {
    switch (kind) {
      case 'create':
        return run(async () => {
          await api.post('/v1/admin/users', {
            displayName, role, phone: phone || null, email: email || null,
            password: password || null, locale: locale || null,
          });
          return `User "${displayName}" created`;
        });
      case 'edit':
        if (!target) return;
        return run(async () => {
          await api.patch(`/v1/admin/users/${target.id}`, {
            displayName, locale, status, role,
          });
          return `User "${displayName}" updated`;
        });
      case 'reset-password':
        if (!target) return;
        return run(async () => {
          await api.post(`/v1/admin/users/${target.id}/reset-password`, { newPassword: password });
          return `Password reset for "${target.displayName}" (all sessions revoked)`;
        });
      case 'force-logout':
        if (!target) return;
        return run(async () => {
          const res = await api.post<{ revokedSessions: number }>(`/v1/admin/users/${target.id}/force-logout`);
          return `Force logout "${target.displayName}": ${res.revokedSessions} session(s) revoked`;
        });
    }
  };

  const selfActions = target && target.id === me.id;

  return (
    <div className="card" role="dialog" aria-modal="true" aria-label={ACTION_LABEL[kind]} data-testid="user-action-panel">
      <h2 style={{ marginTop: 0, fontSize: 14 }}>
        {ACTION_LABEL[kind]}
        {target ? ` — ${target.displayName}` : ''}
      </h2>
      {error && <div className="error-banner" role="alert" data-testid="user-action-error">{error.code}: {error.message}</div>}
      {kind === 'force-logout' ? (
        <p>
          Revoke every live session of <strong>{target?.displayName}</strong>? They must sign in again.
        </p>
      ) : (
        <>
          {(kind === 'create' || kind === 'edit') && (
            <div className="form-grid">
              <label htmlFor="ua-display-name">Display name</label>
              <input id="ua-display-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} data-testid="ua-display-name" />
              {kind === 'create' && (
                <>
                  <label htmlFor="ua-phone">Phone</label>
                  <input id="ua-phone" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="optional" data-testid="ua-phone" />
                  <label htmlFor="ua-email">Email</label>
                  <input id="ua-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="optional" data-testid="ua-email" />
                </>
              )}
              {(kind === 'create' || canBan) && (
                <>
                  <label htmlFor="ua-role">Role</label>
                  <select id="ua-role" value={role} onChange={(e) => setRole(e.target.value)} data-testid="ua-role">
                    {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                  </select>
                  <label htmlFor="ua-status">Status</label>
                  <select id="ua-status" value={status} onChange={(e) => setStatus(e.target.value)} data-testid="ua-status">
                    {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                  </select>
                </>
              )}
              <label htmlFor="ua-locale">Locale</label>
              <select id="ua-locale" value={locale} onChange={(e) => setLocale(e.target.value)} data-testid="ua-locale">
                {['en', 'fr', 'ar'].map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
          )}
          {kind === 'create' && (
            <div className="form-grid">
              <label htmlFor="ua-password">Password</label>
              <input
                id="ua-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                placeholder="min 10 chars" autoComplete="new-password" data-testid="ua-password"
              />
            </div>
          )}
          {kind === 'reset-password' && (
            <div className="form-grid">
              <label htmlFor="ua-new-password">New password</label>
              <input
                id="ua-new-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                placeholder="min 10 chars" autoComplete="new-password" data-testid="ua-new-password"
              />
              <p className="muted">Reseting also revokes every live session of the user (B-04).</p>
            </div>
          )}
          {(kind === 'edit' || kind === 'create') && selfActions && (
            <p className="muted">You cannot change your own role/status (server-enforced, D62).</p>
          )}
        </>
      )}
      <div className="search-row">
        <button className="primary" onClick={() => void submit()} disabled={busy} data-testid="user-action-submit">
          {kind === 'force-logout' ? 'Confirm force logout' : 'Submit'}
        </button>
        <button onClick={onClose} data-testid="user-action-cancel">Cancel</button>
      </div>
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

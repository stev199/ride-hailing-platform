import { useAuth } from '../auth/AuthContext';
import { ALL_PERMS, can } from '../rbac/permissions';

/** Account overview: identity + the exact permission set this role carries. */
export default function OverviewPage() {
  const { user } = useAuth();
  if (!user) return null;

  return (
    <div>
      <h1 className="page-title">Overview</h1>
      <div className="card">
        <table className="data" aria-label="Account details">
          <tbody>
            <tr><th scope="row">Display name</th><td data-testid="ov-name">{user.displayName}</td></tr>
            <tr><th scope="row">Role</th><td>{user.role}</td></tr>
            <tr><th scope="row">Status</th><td>{user.status}</td></tr>
            <tr><th scope="row">Locale</th><td>{user.locale}</td></tr>
            <tr><th scope="row">Phone</th><td>{user.phone ?? '—'}</td></tr>
            <tr><th scope="row">Email</th><td>{user.email ?? '—'}</td></tr>
          </tbody>
        </table>
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 14 }}>Granted permissions ({permissionsCount(user.role)} of {ALL_PERMS.length})</h2>
        <div className="perm-chips" data-testid="perm-chips">
          {ALL_PERMS.map((p) => (
            <span key={p} className="badge" style={{ opacity: can(user.role, p) ? 1 : 0.35 }}>
              {p}{can(user.role, p) ? '' : ' (no)'}
            </span>
          ))}
        </div>
        <p className="muted" style={{ fontSize: 12 }}>
          Server-side RBAC is authoritative (AdminRoutes.withPermission); this mirror only shapes the UI.
        </p>
      </div>
    </div>
  );
}

function permissionsCount(role: Parameters<typeof can>[0]): number {
  return ALL_PERMS.filter((p) => can(role, p)).length;
}

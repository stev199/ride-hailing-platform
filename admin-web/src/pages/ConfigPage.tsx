import { useAuth } from '../auth/AuthContext';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Config center v0 (P1-4): UI shell only. The config service endpoints
 * (signed bundle serving /v1/config, LoginMethodsConfig wiring) land with the
 * config-service task; until then this page renders the planned sections with
 * an explicit empty state. CONFIG_READ gates the page, CONFIG_WRITE will gate
 * the future editors (both exist in the 12-perm matrix since P1-3).
 */
export default function ConfigPage() {
  const { user: me } = useAuth();
  if (!me) return null;
  if (!can(me.role, Perm.CONFIG_READ)) return <ForbiddenPerm perm="CONFIG_READ" />;

  return (
    <div>
      <h1 className="page-title">Config center</h1>
      <div className="info-banner" data-testid="config-empty-state">
        Config service endpoints land with the config-service task (signed bundle serving
        /v1/config + login-methods wiring). This page will list config versions and bundles;
        editors require CONFIG_WRITE.
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0, fontSize: 14 }}>Planned sections</h2>
        <ul>
          <li>Login methods (enable/disable, priority) — LoginMethodsConfig</li>
          <li>Mesh limits (max hops, grace periods) — rides signed bundles with anti-rollback</li>
          <li>Fare config (ride types, estimate vs final policy)</li>
          <li>Versioned config with signed bundles (Ed25519, epoch anti-rollback)</li>
        </ul>
      </div>
    </div>
  );
}

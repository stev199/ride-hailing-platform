import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import type { AdminConfig, ApiError } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Config center (P1-5a): live view over the config service. Reads
 * GET /v1/admin/config/login-methods (CONFIG_READ): the current login-methods
 * state and the signed-bundle version history (Ed25519, epoch anti-rollback).
 * Editors (CONFIG_WRITE -> PATCH) land with the config-editor task; until then
 * this page is read-only, mirroring the users v0 pattern (P1-4).
 */
export default function ConfigPage() {
  const { user: me, api } = useAuth();
  const [config, setConfig] = useState<AdminConfig | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const canRead = !!me && can(me.role, Perm.CONFIG_READ);

  const load = useCallback(async () => {
    if (!canRead) return;
    setBusy(true);
    setError(null);
    try {
      setConfig(await api.get<AdminConfig>('/v1/admin/config/login-methods'));
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

  const lm = config?.loginMethods;
  const channels = lm ? Object.entries(lm.otpChannels) : [];

  return (
    <div>
      <h1 className="page-title">Config center</h1>
      {error && (
        <div className="error-banner" data-testid="config-error">
          {error.code}: {error.message}
        </div>
      )}
      {config && lm && (
        <>
          <div className="ok-banner" data-testid="config-current-version">
            Current config version: {config.currentVersion} (signed bundle, Ed25519, anti-rollback)
          </div>
          <div className="card" data-testid="config-login-methods">
            <h2 style={{ marginTop: 0, fontSize: 14 }}>Login methods (live)</h2>
            <table className="data" aria-label="Login methods" data-testid="config-methods-table">
              <thead>
                <tr>
                  <th>Method / channel</th>
                  <th>Enabled</th>
                  <th>Priority</th>
                  <th>Notes</th>
                </tr>
              </thead>
              <tbody>
                <tr data-testid="config-row-password">
                  <td>Password login</td>
                  <td>{lm.passwordLoginEnabled ? 'yes' : 'no'}</td>
                  <td>-</td>
                  <td>POST /v1/auth/login/password</td>
                </tr>
                <tr data-testid="config-row-autoregister">
                  <td>OTP auto-register</td>
                  <td>{lm.otpAutoRegister ? 'yes' : 'no'}</td>
                  <td>-</td>
                  <td>unknown identifier + verified OTP creates a RIDER</td>
                </tr>
                {channels.map(([name, ch]) => (
                  <tr key={name} data-testid={`config-row-${name}`}>
                    <td>OTP via {name}</td>
                    <td>{ch.enabled ? 'yes' : 'no'}</td>
                    <td>{ch.priority}</td>
                    <td>{ch.devOnly ? 'devOnly: disabled in production runtimes' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card" data-testid="config-history">
            <h2 style={{ marginTop: 0, fontSize: 14 }}>Signed bundle history</h2>
            <table className="data" aria-label="Config version history" data-testid="config-history-table">
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Issued (epoch sec)</th>
                  <th>Changed by</th>
                  <th>Change</th>
                </tr>
              </thead>
              <tbody>
                {config.history.map((v) => (
                  <tr key={v.version} data-testid={`config-history-${v.version}`}>
                    <td>{v.version}</td>
                    <td>{v.issuedAtEpochSec}</td>
                    <td>{v.changedBy}</td>
                    <td>{v.change}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {!config && !error && busy && <div className="info-banner">Loading config…</div>}
    </div>
  );
}

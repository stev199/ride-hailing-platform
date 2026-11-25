import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import type { AdminConfig, ApiError, ChannelPatch, LoginMethodsPatch, LoginMethodsState } from '../api/types';
import { can, Perm } from '../rbac/permissions';
import { ForbiddenPerm } from './UsersPage';

/**
 * Config center (P1-5a read view + P1-5a2 editors): GET /v1/admin/config/login-methods
 * (CONFIG_READ) shows the live login-methods state and the signed-bundle version
 * history (Ed25519, epoch anti-rollback). CONFIG_WRITE opens the editor: toggles +
 * priority inputs that PATCH only the changed fields (absent fields are server-side
 * no-ops, openapi LoginMethodsPatch). devOnly is a deployment property and is NOT
 * editable (D37); priorities must stay distinct across enabled channels - that rule
 * and any invalid input are enforced by the server and surfaced verbatim.
 */

/** Editor-local state mirrors LoginMethodsState with the priority kept raw for verbatim 400s. */
interface EditState {
  passwordLoginEnabled: boolean;
  otpAutoRegister: boolean;
  channels: Record<string, { enabled: boolean; priorityRaw: string }>;
}

function editStateOf(lm: LoginMethodsState): EditState {
  const channels: EditState['channels'] = {};
  for (const [name, ch] of Object.entries(lm.otpChannels)) {
    channels[name] = { enabled: ch.enabled, priorityRaw: String(ch.priority) };
  }
  return { passwordLoginEnabled: lm.passwordLoginEnabled, otpAutoRegister: lm.otpAutoRegister, channels };
}

/**
 * Diff the editor state against the loaded snapshot: only changed fields go on the
 * wire, so an untouched editor can never produce a 400 empty_patch (and the UI also
 * disables Save until something changed - the server still owns the contract).
 */
function buildPatch(lm: LoginMethodsState, edit: EditState): LoginMethodsPatch {
  const patch: LoginMethodsPatch = {};
  if (edit.passwordLoginEnabled !== lm.passwordLoginEnabled) patch.passwordLoginEnabled = edit.passwordLoginEnabled;
  if (edit.otpAutoRegister !== lm.otpAutoRegister) patch.otpAutoRegister = edit.otpAutoRegister;
  const channels: Record<string, ChannelPatch> = {};
  for (const [name, ch] of Object.entries(edit.channels)) {
    const old = lm.otpChannels[name];
    if (!old) continue;
    const chPatch: ChannelPatch = {};
    if (ch.enabled !== old.enabled) chPatch.enabled = ch.enabled;
    const n = Number(ch.priorityRaw);
    if (ch.priorityRaw !== '' && Number.isFinite(n) && n !== old.priority) chPatch.priority = n;
    if (Object.keys(chPatch).length > 0) channels[name] = chPatch;
  }
  if (Object.keys(channels).length > 0) patch.otpChannels = channels;
  return patch;
}

export default function ConfigPage() {
  const { user: me, api } = useAuth();
  const { t } = useTranslation();
  const [config, setConfig] = useState<AdminConfig | null>(null);
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
  const edit = editing && lm ? editStateOf(lm) : null;

  return (
    <div>
      <h1 className="page-title">{t('config.title')}</h1>
      {notice && (
        <div className="ok-banner" role="status" data-testid="config-action-notice">
          {notice}
        </div>
      )}
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
          {canWrite && !editing && (
            <div className="search-row">
              <button className="primary" onClick={() => { setError(null); setEditing(true); }} data-testid="config-edit">
                {t('config.edit')}
              </button>
            </div>
          )}
          {editing && edit && lm && (
            <ConfigEditPanel
              lm={lm}
              edit={edit}
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
          <div className="card" data-testid="config-login-methods">
            <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('config.loginMethodsLive')}</h2>
            <table className="data" aria-label={t('config.loginMethods')} data-testid="config-methods-table">
              <thead>
                <tr>
                  <th>{t('config.methodChannel')}</th>
                  <th>{t('config.enabled')}</th>
                  <th>{t('config.priority')}</th>
                  <th>{t('config.notes')}</th>
                </tr>
              </thead>
              <tbody>
                <tr data-testid="config-row-password">
                  <td>{t('config.passwordLogin')}</td>
                  <td>{lm.passwordLoginEnabled ? 'yes' : 'no'}</td>
                  <td>-</td>
                  <td>POST /v1/auth/login/password</td>
                </tr>
                <tr data-testid="config-row-autoregister">
                  <td>{t('config.otpAutoRegister')}</td>
                  <td>{lm.otpAutoRegister ? 'yes' : 'no'}</td>
                  <td>-</td>
                  <td>{t('config.devOnlyNote')}</td>
                </tr>
                {channels.map(([name, ch]) => (
                  <tr key={name} data-testid={`config-row-${name}`}>
                    <td>{t('config.otpVia', { name })}</td>
                    <td>{ch.enabled ? 'yes' : 'no'}</td>
                    <td>{ch.priority}</td>
                    <td>{ch.devOnly ? 'devOnly: disabled in production runtimes' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card" data-testid="config-history">
            <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('config.bundleHistory')}</h2>
            <table className="data" aria-label={t('config.versionHistory')} data-testid="config-history-table">
              <thead>
                <tr>
                  <th>{t('config.version')}</th>
                  <th>{t('config.issuedAt')}</th>
                  <th>{t('config.changedBy')}</th>
                  <th>{t('config.change')}</th>
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
      {!config && !error && busy && <div className="info-banner">{t('config.loading')}</div>}
    </div>
  );
}

/**
 * Editor panel (P1-5a2). Save sends ONLY the changed fields; until then it stays
 * disabled (a no-op patch is a server 400 by contract). Server errors are shown
 * verbatim - the backend is the single authority on the config contract.
 */
function ConfigEditPanel({
  lm,
  edit,
  api,
  onClose,
  onDone,
}: {
  lm: LoginMethodsState;
  edit: EditState;
  api: { patch: <T>(p: string, b?: unknown) => Promise<T> };
  onClose: () => void;
  onDone: (notice: string) => void | Promise<void>;
}) {
  const { t } = useTranslation();
  const [state, setState] = useState<EditState>(edit);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const patch = useMemo(() => buildPatch(lm, state), [lm, state]);
  const dirty = Object.keys(patch).length > 0;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.patch('/v1/admin/config/login-methods', patch);
      await onDone(t('config.saved'));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card" role="dialog" aria-modal="true" aria-label={t('config.edit')} data-testid="config-edit-panel">
      <h2 style={{ marginTop: 0, fontSize: 14 }}>{t('config.edit')}</h2>
      {error && <div className="error-banner" role="alert" data-testid="config-edit-error">{error.code}: {error.message}</div>}
      <div className="form-grid">
        <label htmlFor="cfg-password">{t('config.passwordLogin')}</label>
        <input
          id="cfg-password" type="checkbox" checked={state.passwordLoginEnabled}
          onChange={(e) => setState({ ...state, passwordLoginEnabled: e.target.checked })}
          data-testid="cfg-toggle-password"
        />
        <label htmlFor="cfg-autoregister">{t('config.otpAutoRegister')}</label>
        <input
          id="cfg-autoregister" type="checkbox" checked={state.otpAutoRegister}
          onChange={(e) => setState({ ...state, otpAutoRegister: e.target.checked })}
          data-testid="cfg-toggle-autoregister"
        />
        {Object.entries(state.channels).map(([name, ch]) => (
          <div key={name} style={{ display: 'contents' }}>
            <label htmlFor={`cfg-enabled-${name}`}>{t('config.otpVia', { name })}</label>
            <span>
              <input
                id={`cfg-enabled-${name}`} type="checkbox" checked={ch.enabled}
                aria-label={`${name} enabled`}
                onChange={(e) =>
                  setState({
                    ...state,
                    channels: { ...state.channels, [name]: { ...ch, enabled: e.target.checked } },
                  })
                }
                data-testid={`cfg-enabled-${name}`}
              />
            </span>
            <label htmlFor={`cfg-priority-${name}`}>{name} priority</label>
            <input
              id={`cfg-priority-${name}`} type="number" inputMode="numeric" style={{ width: 80 }}
              value={ch.priorityRaw} aria-label={`${name} priority`}
              onChange={(e) =>
                setState({
                  ...state,
                  channels: { ...state.channels, [name]: { ...ch, priorityRaw: e.target.value } },
                })
              }
              data-testid={`cfg-priority-${name}`}
            />
          </div>
        ))}
      </div>
      <p className="muted">{t('config.editorNote')}</p>
      <div className="search-row">
        <button className="primary" onClick={() => void submit()} disabled={busy || !dirty} data-testid="config-edit-submit">
          {t('config.save')}
        </button>
        <button onClick={onClose} data-testid="config-edit-cancel">{t('config.cancel')}</button>
      </div>
    </div>
  );
}

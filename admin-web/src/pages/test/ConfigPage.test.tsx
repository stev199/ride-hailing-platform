import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import ConfigPage from '../ConfigPage';
import { tokenStore } from '../../api/client';
import type { AdminConfig } from '../../api/types';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en',
};

const CONFIG: AdminConfig = {
  currentVersion: 2,
  loginMethods: {
    passwordLoginEnabled: true,
    otpAutoRegister: true,
    otpChannels: {
      SMS: { enabled: true, priority: 10, devOnly: true },
      TELEGRAM: { enabled: false, priority: 20, devOnly: false },
      EMAIL: { enabled: false, priority: 30, devOnly: false },
    },
  },
  history: [
    { version: 2, issuedAtEpochSec: 1737610000, config: '{"loginMethods":{}}', signature: 'sig2', changedBy: 'u1', change: 'login_methods_updated' },
    { version: 1, issuedAtEpochSec: 1737600000, config: '{"loginMethods":{}}', signature: 'sig1', changedBy: 'system', change: 'bootstrap' },
  ],
};

interface FetchCall { url: string; method: string; body: unknown }

/**
 * Stubbed backend: refresh + /v1/me boot the shell, GET/PATCH the config endpoint.
 * `patchResponder` decides the PATCH answer per test; the request is recorded in `calls`.
 */
function renderPage(role: string, opts: { patchResponder?: () => { status: number; body: unknown } } = {}) {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init?: { method?: string; body?: string }) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body ? JSON.parse(init.body) : null });
      if (url.includes('/v1/auth/refresh')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }
      if (url.includes('/v1/me')) {
        return Promise.resolve(new Response(JSON.stringify({ ...ADMIN, role }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/v1/admin/config/login-methods')) {
        if (method === 'PATCH') {
          const r = opts.patchResponder?.() ?? { status: 200, body: CONFIG.history[0] };
          return Promise.resolve(new Response(JSON.stringify(r.body), { status: r.status, headers: { 'Content-Type': 'application/json' } }));
        }
        return Promise.resolve(new Response(JSON.stringify(CONFIG), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  const page = render(
    <MemoryRouter initialEntries={['/config']}>
      <AuthProvider>
        <ConfigPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls, page };
}

describe('ConfigPage editors (P1-5a2)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('PATCHes only the changed fields and reloads after save', async () => {
    const { calls } = renderPage('ADMIN_SUPER');
    await waitFor(() => expect(screen.getByTestId('config-current-version').textContent).toContain('version: 2'));

    await userEvent.click(screen.getByTestId('config-edit'));
    const panel = screen.getByTestId('config-edit-panel');
    expect(panel).toBeInTheDocument();

    // Save is disabled until something changed (an untouched editor can never 400 empty_patch).
    expect(screen.getByTestId('config-edit-submit')).toBeDisabled();

    await userEvent.click(screen.getByTestId('cfg-toggle-password'));
    await userEvent.clear(screen.getByTestId('cfg-priority-TELEGRAM'));
    await userEvent.type(screen.getByTestId('cfg-priority-TELEGRAM'), '25');
    await userEvent.click(screen.getByTestId('config-edit-submit'));

    await waitFor(() => expect(screen.getByTestId('config-action-notice').textContent).toContain('signed config version'));
    const patchCall = calls.find((c) => c.method === 'PATCH');
    expect(patchCall?.url).toContain('/v1/admin/config/login-methods');
    expect(patchCall?.body).toEqual({
      passwordLoginEnabled: false,
      otpChannels: { TELEGRAM: { priority: 25 } },
    });
    // Editor closed after success; state reloads (GET again).
    expect(screen.queryByTestId('config-edit-panel')).not.toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes('/v1/admin/config/login-methods') && c.method === 'GET').length).toBeGreaterThanOrEqual(2);
  });

  it('surfaces server validation errors verbatim and keeps the editor open', async () => {
    renderPage('ADMIN_SUPER', {
      patchResponder: () => ({ status: 400, body: { code: 'duplicate_priority', message: 'Validation failed: otpChannels.priority', traceId: null } }),
    });
    await waitFor(() => expect(screen.getByTestId('config-edit')).toBeInTheDocument());

    await userEvent.click(screen.getByTestId('config-edit'));
    await userEvent.click(screen.getByTestId('cfg-toggle-password'));
    await userEvent.click(screen.getByTestId('config-edit-submit'));

    const err = screen.getByTestId('config-edit-error');
    expect(err.textContent).toContain('duplicate_priority');
    expect(screen.getByTestId('config-edit-panel')).toBeInTheDocument();
  });

  it('CONFIG_READ-only roles (ADMIN_SUPPORT) see the state but no editor', async () => {
    renderPage('ADMIN_SUPPORT');
    await waitFor(() => expect(screen.getByTestId('config-current-version').textContent).toContain('version: 2'));
    expect(screen.queryByTestId('config-edit')).not.toBeInTheDocument();
    expect(screen.getByTestId('config-methods-table')).toBeInTheDocument();
  });
});

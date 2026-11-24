import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import LoginPage from '../LoginPage';
import { tokenStore } from '../../api/client';

const jsonRes = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const ADMIN = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en',
};

describe('LoginPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('submits credentials and stores tokens on success', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      jsonRes(200, {
        user: ADMIN,
        tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 },
        created: false,
      }),
    );
    render(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>,
    );
    await userEvent.type(screen.getByLabelText('Phone or email'), 'ops@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'secret-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(tokenStore.getRefresh()).toBe('rt'));
  });

  it('shows the API error banner on 401 without storing tokens', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      jsonRes(401, { code: 'unauthorized', message: 'Unknown identifier or wrong password', traceId: null }),
    );
    render(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>,
    );
    await userEvent.type(screen.getByLabelText('Phone or email'), 'nope@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByTestId('login-error')).toBeInTheDocument());
    expect(screen.getByTestId('login-error').textContent).toContain('unauthorized');
    expect(tokenStore.getRefresh()).toBeNull();
  });
});

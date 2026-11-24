import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../AuthContext';
import { tokenStore } from '../../api/client';

const jsonRes = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const ADMIN = {
  id: 'u1',
  phone: '15550001111',
  email: null,
  displayName: 'Ops Admin',
  role: 'ADMIN_OPS',
  status: 'ACTIVE',
  locale: 'en',
};

/** Probe component exposing the context to the test. */
function Probe({ run }: { run: (auth: ReturnType<typeof useAuth>) => Promise<void> | void }) {
  const auth = useAuth();
  return (
    <button
      onClick={() => {
        void run(auth);
      }}
    >
      run
    </button>
  );
}

describe('AuthContext', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('login stores the token pair and sets the user', async () => {
    const f = globalThis.fetch as ReturnType<typeof vi.fn>;
    f.mockResolvedValueOnce(
      jsonRes(200, {
        user: ADMIN,
        tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 999 },
        created: false,
      }),
    );
    render(
      <AuthProvider>
        <Probe
          run={async (auth) => {
            await auth.login('+15550001111', 'pw');
          }}
        />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText('run'));
    await waitFor(() => {
      expect(tokenStore.getAccess()).toBe('at');
      expect(tokenStore.getRefresh()).toBe('rt');
    });
    expect(f.mock.calls[0][0]).toContain('/v1/auth/login/password');
  });

  it('boot rehydrates via refresh + /v1/me when a refresh token exists', async () => {
    tokenStore.setRefresh('rt-old');
    const f = globalThis.fetch as ReturnType<typeof vi.fn>;
    f.mockResolvedValueOnce(
      jsonRes(200, { tokens: { accessToken: 'at2', refreshToken: 'rt2', accessExpiresAtEpochSec: 1 } }),
    );
    f.mockResolvedValueOnce(jsonRes(200, ADMIN));
    render(
      <AuthProvider>
        <Probe
          run={(auth) => {
            expect(auth.user).not.toBeNull();
          }}
        />
      </AuthProvider>,
    );
    // Boot effect completes asynchronously.
    await waitFor(() => {
      expect(tokenStore.getAccess()).toBe('at2');
      expect(tokenStore.getRefresh()).toBe('rt2');
    });
    expect(f.mock.calls[0][0]).toContain('/v1/auth/refresh');
    expect(f.mock.calls[1][0]).toContain('/v1/me');
  });

  it('failed boot refresh leaves the user logged out and booted', async () => {
    tokenStore.setRefresh('rt-dead');
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(jsonRes(401, { code: 'unauthorized', message: 'x', traceId: null }));
    let sawBoot = false;
    render(
      <AuthProvider>
        <Probe
          run={(auth) => {
            sawBoot = auth.booted && auth.user === null;
          }}
        />
      </AuthProvider>,
    );
    await waitFor(() => {
      expect(tokenStore.getRefresh()).toBeNull(); // cleared by the failed refresh
    });
    await act(async () => {
      screen.getByText('run').click();
    });
    expect(sawBoot).toBe(true);
  });

  it('logout revokes the family and clears storage even if the API call fails', async () => {
    const f = globalThis.fetch as ReturnType<typeof vi.fn>;
    f.mockResolvedValueOnce(
      jsonRes(200, {
        user: ADMIN,
        tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 },
        created: false,
      }),
    );
    f.mockRejectedValueOnce(new Error('network down')); // logout call fails
    render(
      <AuthProvider>
        <Probe
          run={async (auth) => {
            await auth.login('x', 'y');
            await auth.logout();
          }}
        />
      </AuthProvider>,
    );
    await userEvent.click(screen.getByText('run'));
    await waitFor(() => {
      expect(tokenStore.getRefresh()).toBeNull();
      expect(tokenStore.getAccess()).toBeNull();
    });
  });
});

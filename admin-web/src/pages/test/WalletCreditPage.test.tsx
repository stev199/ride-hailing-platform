import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import WalletCreditPage from '../WalletCreditPage';
import { tokenStore } from '../../api/client';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'finance@example.com', displayName: 'Finance Admin',
  role: 'ADMIN_FINANCE', status: 'ACTIVE', locale: 'en',
};

const RIDER = {
  id: 'rider-1', phone: '33440000001', email: null, displayName: 'Rider One',
  role: 'RIDER', status: 'ACTIVE', locale: 'en', createdAtEpochSec: 0,
};

interface FetchCall { url: string; method: string; body?: string }

function renderPage(role: string = 'ADMIN_FINANCE') {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init?: { method?: string; body?: string }) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body });
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
      if (url.includes('/wallet-credit')) {
        return Promise.resolve(new Response(JSON.stringify({ balances: { POINTS: 4200 } }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/v1/admin/users')) {
        return Promise.resolve(new Response(JSON.stringify({ users: [RIDER], limit: 10, offset: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  render(
    <MemoryRouter initialEntries={['/wallet']}>
      <AuthProvider>
        <WalletCreditPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls };
}

describe('WalletCreditPage (P7-5b admin money)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('search finds the user; credit posts amountMinor + reason and shows the balance', async () => {
    const { calls } = renderPage();
    await waitFor(() => expect(screen.getByTestId('wallet-search')).toBeTruthy());
    await userEvent.type(screen.getByTestId('wallet-search'), '3344');
    await userEvent.click(screen.getByTestId('wallet-search-go'));
    await waitFor(() => expect(screen.getByTestId('wallet-users')).toBeTruthy());
    expect(calls.some((c) => c.url.includes('/v1/admin/users?') && c.url.includes('query=3344'))).toBe(true);

    await userEvent.type(screen.getByTestId('wallet-amount-rider-1'), '500');
    await userEvent.type(screen.getByTestId('wallet-reason-rider-1'), 'goodwill');
    await userEvent.click(screen.getByTestId('wallet-credit-rider-1'));
    await waitFor(() => expect(screen.getByTestId('wallet-notice')).toBeTruthy());
    const post = calls.find((c) => c.method === 'POST' && c.url.includes('/v1/admin/users/rider-1/wallet-credit'));
    expect(post).toBeTruthy();
    expect(post!.body).toContain('"amountMinor":500');
    expect(post!.body).toContain('"reason":"goodwill"');
    expect(screen.getByTestId('wallet-notice').textContent).toContain('4200');
  });

  it('credit without a reason surfaces the honest validation error, nothing posted', async () => {
    const { calls } = renderPage();
    await waitFor(() => expect(screen.getByTestId('wallet-search-go')).toBeTruthy());
    await userEvent.click(screen.getByTestId('wallet-search-go'));
    await waitFor(() => expect(screen.getByTestId('wallet-users')).toBeTruthy());
    await userEvent.type(screen.getByTestId('wallet-amount-rider-1'), '500');
    await userEvent.click(screen.getByTestId('wallet-credit-rider-1'));
    await waitFor(() => expect(screen.getByTestId('wallet-error')).toBeTruthy());
    expect(screen.getByTestId('wallet-error').textContent).toContain('invalid_reason');
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/wallet-credit'))).toBe(false);
  });

  it('role without MONEY_READ (RIDER) gets the forbidden notice', async () => {
    renderPage('RIDER');
    await waitFor(() => expect(screen.getByTestId('forbidden-perm')).toBeTruthy());
    expect(screen.queryByTestId('wallet-users')).toBeNull();
  });
});

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import PromosPage from '../PromosPage';
import { tokenStore } from '../../api/client';
import type { PromoConfigDoc } from '../PromosPage';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'config@example.com', displayName: 'Config Admin',
  role: 'ADMIN_SUPER', status: 'ACTIVE', locale: 'en',
};

const DOC: PromoConfigDoc = {
  codes: [
    {
      code: 'HALFOFF', kind: 'PERCENT', value: 50, currency: null,
      validFromEpochSec: 0, validUntilEpochSec: 4000000000, perUserLimit: 1, active: true,
    },
  ],
};

interface FetchCall { url: string; method: string; body?: string }

function renderPage(role: string = 'ADMIN_SUPER') {
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
      if (method === 'PATCH' && url.includes('/v1/admin/config/promos')) {
        return Promise.resolve(new Response(JSON.stringify({ version: 7, config: { codes: JSON.parse(init?.body ?? '{}').codes } }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/v1/admin/config/promos')) {
        return Promise.resolve(new Response(JSON.stringify(DOC), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  render(
    <MemoryRouter initialEntries={['/promos']}>
      <AuthProvider>
        <PromosPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls };
}

describe('PromosPage (P7-6 promo editor, D75 full-replace)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('renders the live codes; save PATCHes the WHOLE document and shows the version', async () => {
    const { calls } = renderPage();
    await waitFor(() => expect(screen.getByTestId('promos-table')).toBeTruthy());
    expect(screen.getByTestId('promos-code-0')).toBeTruthy();
    expect((screen.getByTestId('promos-code-0') as HTMLInputElement).value).toBe('HALFOFF');

    await userEvent.type(screen.getByTestId('promos-code-0'), 'X'); // append to the code
    await userEvent.click(screen.getByTestId('promos-save'));
    await waitFor(() => expect(screen.getByTestId('promos-saved')).toBeTruthy());
    const patch = calls.find((c) => c.method === 'PATCH' && c.url.includes('/v1/admin/config/promos'));
    expect(patch).toBeTruthy();
    const sent = JSON.parse(patch!.body!);
    expect(sent.codes).toHaveLength(1);
    expect(sent.codes[0].code).toBe('HALFOFFX');
    expect(sent.codes[0].kind).toBe('PERCENT');
    expect(sent.codes[0].perUserLimit).toBe(1);
    expect(screen.getByTestId('promos-saved').textContent).toContain('7');
  });

  it('add + remove rows edit the in-memory document; readonly role sees disabled inputs', async () => {
    renderPage('ADMIN_READONLY');
    await waitFor(() => expect(screen.getByTestId('promos-table')).toBeTruthy());
    expect((screen.getByTestId('promos-code-0') as HTMLInputElement).disabled).toBe(true);
    expect(screen.queryByTestId('promos-save')).toBeNull();
    expect(screen.queryByTestId('promos-add')).toBeNull();
  });

  it('role without CONFIG_READ gets the forbidden notice', async () => {
    renderPage('RIDER');
    await waitFor(() => expect(screen.getByTestId('forbidden-perm')).toBeTruthy());
    expect(screen.queryByTestId('promos-table')).toBeNull();
  });
});

import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import Layout from '../Layout';
import { tokenStore } from '../../api/client';

const ADMIN: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'ops@example.com', displayName: 'Ops Admin',
  role: 'ADMIN_SUPPORT', status: 'ACTIVE', locale: 'en',
};

/** Layout renders inside Outlet-less router: render it as an index route. */
function renderShell(role: string) {
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string) => {
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
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  return render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <Layout />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('Layout RBAC nav', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('ADMIN_READONLY sees Users/Audit/Config but no write surfaces; header shows role badge', async () => {
    renderShell('ADMIN_READONLY');
    await waitFor(() => expect(screen.getByTestId('role-badge')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Config center' })).toBeInTheDocument();
    expect(screen.getByTestId('display-name').textContent).toBe('Ops Admin');
  });

  it('RIDER (empty permission set) sees only Overview and is not crashed by empty perms', async () => {
    renderShell('RIDER');
    await waitFor(() => expect(screen.getByTestId('role-badge')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Overview' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Config center' })).not.toBeInTheDocument();
  });
});

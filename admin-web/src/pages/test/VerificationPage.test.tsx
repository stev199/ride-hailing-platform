import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import VerificationPage from '../VerificationPage';
import { tokenStore } from '../../api/client';
import type { VerificationListResponse, VerificationSubmission } from '../VerificationPage';

const VERIFIER: Record<string, unknown> = {
  id: 'u1', phone: null, email: 'verifier@example.com', displayName: 'Verifier',
  role: 'ADMIN_VERIFIER', status: 'ACTIVE', locale: 'en',
};

function row(id: string, overrides: Partial<VerificationSubmission> = {}): VerificationSubmission {
  return {
    id,
    userId: 'driver-1',
    licenseNumber: 'FR-12345678',
    vehiclePlate: 'AA-123-BB',
    declaredDocs: ['license_front', 'insurance'],
    status: 'PENDING',
    submittedAtEpochSec: 1764200000,
    reviewedAtEpochSec: null,
    reviewerId: null,
    rejectReason: null,
    ...overrides,
  };
}

const PENDING_A = row('aaaaaaaa-1111-2222-3333-444444444444');
const PENDING_B = row('bbbbbbbb-1111-2222-3333-444444444444', { licenseNumber: 'FR-9999' });
const LIST: VerificationListResponse = { submissions: [PENDING_B, PENDING_A], limit: 25, offset: 0 };

interface FetchCall { url: string; method: string; body?: unknown }

/** Stubbed backend (same harness as TripsPage.test): /v1/me + the verification endpoints. */
function renderPage(role: string) {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init?: { method?: string; body?: string }) => {
      const method = init?.method ?? 'GET';
      const body = init?.body ? JSON.parse(init.body) : undefined;
      calls.push({ url, method, body });
      if (url.includes('/v1/auth/refresh')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ tokens: { accessToken: 'at', refreshToken: 'rt', accessExpiresAtEpochSec: 1 } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }
      if (url.includes('/v1/me')) {
        return Promise.resolve(new Response(JSON.stringify({ ...VERIFIER, role }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/reject')) {
        // Exactly like the server: a valid reason -> 200 + reviewed row; an
        // unexpected reason -> 400 invalid_reason (verbatim error surface).
        if (method === 'POST' && body && body.reason === 'plate mismatch') {
          return Promise.resolve(
            new Response(JSON.stringify({ ...PENDING_A, status: 'REJECTED', rejectReason: 'plate mismatch', reviewedAtEpochSec: 1764200600, reviewerId: 'u1' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
          );
        }
        return Promise.resolve(new Response(JSON.stringify({ code: 'invalid_reason', message: 'A reject reason (1..512 chars) is required' }), { status: 400, headers: { 'Content-Type': 'application/json' } }));
      }
      if (url.includes('/approve')) {
        return Promise.resolve(
          new Response(JSON.stringify({ ...PENDING_B, status: 'APPROVED', reviewedAtEpochSec: 1764200700, reviewerId: 'u1' }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
        );
      }
      if (url.includes('/v1/admin/verification')) {
        return Promise.resolve(new Response(JSON.stringify(LIST), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return Promise.resolve(new Response(JSON.stringify({ code: 'not_found', message: 'x', traceId: null }), { status: 404, headers: { 'Content-Type': 'application/json' } }));
    }),
  );
  localStorage.setItem('admin.refreshToken', 'rt');
  sessionStorage.setItem('admin.accessToken', 'at');
  render(
    <MemoryRouter initialEntries={['/verification']}>
      <AuthProvider>
        <VerificationPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { calls };
}

describe('VerificationPage (Phase 6 verification center)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    tokenStore.clearAccess();
    tokenStore.clearRefresh();
  });

  it('renders the queue with declared doc badges and pending actions', async () => {
    renderPage('ADMIN_VERIFIER');
    await waitFor(() => expect(screen.getByTestId('verification-table')).toBeTruthy());
    const rows = screen.getAllByTestId(/^verification-row-/);
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('FR-9999');
    expect(rows[1].textContent).toContain('FR-12345678');
    // Declared docs render as badges (v1: names only - S-03 images are not pretended).
    expect(screen.getAllByText('license_front').length).toBe(2);
    expect(screen.getAllByText('insurance').length).toBe(2);
  });

  it('verifier approves a pending submission - POST leaves with the right path and the row updates', async () => {
    renderPage('ADMIN_VERIFIER');
    await waitFor(() => expect(screen.getByTestId('verification-table')).toBeTruthy());
    await userEvent.click(screen.getByTestId(`verification-approve-${PENDING_B.id}`));
    await waitFor(() => expect(screen.getByTestId(`verification-status-${PENDING_B.id}`).textContent).toContain('APPROVED'));
  });

  it('reject requires a reason - empty confirm is disabled, server 400 surfaces verbatim', async () => {
    renderPage('ADMIN_VERIFIER');
    await waitFor(() => expect(screen.getByTestId('verification-table')).toBeTruthy());
    await userEvent.click(screen.getByTestId(`verification-reject-open-${PENDING_A.id}`));
    // Empty reason -> the confirm button is disabled (client guard mirrors the 400).
    const confirm = screen.getByTestId(`verification-reject-confirm-${PENDING_A.id}`) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    // Server-side 400 (invalid_reason) renders verbatim in the error banner.
    await userEvent.type(screen.getByTestId(`verification-reason-input-${PENDING_A.id}`), 'x');
    await userEvent.click(confirm);
    await waitFor(() => expect(screen.getByTestId('verification-error').textContent).toContain('invalid_reason'));
    // A real reason round-trips and the row shows the reject reason.
    await userEvent.clear(screen.getByTestId(`verification-reason-input-${PENDING_A.id}`));
    await userEvent.type(screen.getByTestId(`verification-reason-input-${PENDING_A.id}`), 'plate mismatch');
    await userEvent.click(confirm);
    await waitFor(() => expect(screen.queryByTestId('verification-error')).toBeNull());
    expect(screen.getByTestId(`verification-status-${PENDING_A.id}`).textContent).toContain('plate mismatch');
  });

  it('status filter refetches with the query param', async () => {
    const { calls } = renderPage('ADMIN_OPS');
    await waitFor(() => expect(screen.getByTestId('verification-table')).toBeTruthy());
    const callsBefore = calls.length;
    await userEvent.selectOptions(screen.getByTestId('verification-status-filter'), 'REJECTED');
    await waitFor(() => expect(calls.length > callsBefore).toBe(true));
    expect(calls.some((c) => c.url.includes('status=REJECTED'))).toBe(true);
  });

  it('role without VERIFICATION_READ (RIDER) gets the forbidden notice, no queue fetch', async () => {
    renderPage('RIDER');
    await waitFor(() => expect(screen.getByTestId('forbidden-perm')).toBeTruthy());
    expect(screen.queryByTestId('verification-table')).toBeNull();
  });
});

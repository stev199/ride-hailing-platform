import type { Page, Route } from '@playwright/test';

/** User shape per openapi User schema. */
export interface MockUser {
  id: string;
  phone: string | null;
  email: string | null;
  displayName: string;
  role: string;
  status: string;
  locale: string;
  createdAtEpochSec: number;
}

export const USERS: MockUser[] = [
  { id: 'u1', phone: '15550001111', email: null, displayName: 'Ops Admin', role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en', createdAtEpochSec: 1737600000 },
  { id: 'u2', phone: null, email: 'rider@example.com', displayName: 'Ria Rider', role: 'RIDER', status: 'BANNED_TEMP', locale: 'fr', createdAtEpochSec: 1737500000 },
  { id: 'u3', phone: '15550002222', email: 'driver@example.com', displayName: 'Dan Driver', role: 'DRIVER', status: 'ACTIVE', locale: 'en', createdAtEpochSec: 1737400000 },
];

const AUDIT_EVENTS = [
  { seq: 3, type: 'admin.user.updated', occurredAt: '2026-11-24T05:00:00Z', payloadJson: '{"actorId":"u1","after":{"role":"RIDER"},"before":{"role":"DRIVER"}}', hash: 'abcd1234abcd1234abcd' },
  { seq: 2, type: 'admin.password_reset', occurredAt: '2026-11-24T04:30:00Z', payloadJson: '{"actorId":"u1","sessionsRevoked":"all","targetId":"u2"}', hash: 'beef5678beef5678beef' },
  { seq: 1, type: 'system.admin_bootstrap', occurredAt: '2026-11-24T04:00:00Z', payloadJson: '{"actorId":"system","userId":"u1"}', hash: 'cafe9abccafe9abccafe9' },
];

const TOKENS = { accessToken: 'e2e-access', refreshToken: 'e2e-refresh', accessExpiresAtEpochSec: 4102444800 };

const json = (route: Route, status: number, body: unknown): Promise<void> =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/**
 * Installs route mocks for every /v1/** call the admin shell makes.
 * `role` controls the identity returned by login / refresh + /v1/me.
 */
export async function mockApi(page: Page, role: string): Promise<void> {
  const user: MockUser = { id: 'u1', phone: '15550001111', email: null, displayName: 'Ops Admin', role, status: 'ACTIVE', locale: 'en' };

  await page.route('**/v1/auth/login/password', (route) =>
    json(route, 200, { user, tokens: TOKENS, created: false }));
  await page.route('**/v1/auth/refresh', (route) => json(route, 200, { tokens: TOKENS }));
  await page.route('**/v1/auth/logout', (route) => route.fulfill({ status: 204 }));
  await page.route('**/v1/me', (route) => json(route, 200, user));
  await page.route('**/v1/admin/users?*', (route) =>
    json(route, 200, { users: USERS, limit: 20, offset: 0 }));
  await page.route('**/v1/admin/audit?*', (route) =>
    json(route, 200, { verifyOk: true, events: AUDIT_EVENTS }));
}

/** Seeds a session so the shell boots straight into the authenticated area. */
export async function seedSession(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('admin.refreshToken', 'seeded-refresh');
    sessionStorage.setItem('admin.accessToken', 'seeded-access');
  });
}

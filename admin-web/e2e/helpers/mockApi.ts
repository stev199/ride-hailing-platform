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

/** Fresh copy per test - the mock mutates USERS, so tests reset it in beforeEach. */
export function resetUsers(): void {
  USERS.splice(0, USERS.length, {
    id: 'u1', phone: '15550001111', email: null, displayName: 'Ops Admin', role: 'ADMIN_OPS', status: 'ACTIVE', locale: 'en', createdAtEpochSec: 1737600000,
  }, {
    id: 'u2', phone: null, email: 'rider@example.com', displayName: 'Ria Rider', role: 'RIDER', status: 'BANNED_TEMP', locale: 'fr', createdAtEpochSec: 1737500000,
  }, {
    id: 'u3', phone: '15550002222', email: 'driver@example.com', displayName: 'Dan Driver', role: 'DRIVER', status: 'ACTIVE', locale: 'en', createdAtEpochSec: 1737400000,
  });
}

const AUDIT_EVENTS = [
  { seq: 3, type: 'admin.user.updated', occurredAt: '2026-11-24T05:00:00Z', payloadJson: '{"actorId":"u1","after":{"role":"RIDER"},"before":{"role":"DRIVER"}}', hash: 'abcd1234abcd1234abcd' },
  { seq: 2, type: 'admin.password_reset', occurredAt: '2026-11-24T04:30:00Z', payloadJson: '{"actorId":"u1","sessionsRevoked":"all","targetId":"u2"}', hash: 'beef5678beef5678beef' },
  { seq: 1, type: 'system.admin_bootstrap', occurredAt: '2026-11-24T04:00:00Z', payloadJson: '{"actorId":"system","userId":"u1"}', hash: 'cafe9abccafe9abccafe9' },
];

const TOKENS = { accessToken: 'e2e-access', refreshToken: 'e2e-refresh', accessExpiresAtEpochSec: 4102444800 };

/** P1-5a config service fixture: current login methods + 2-version history. */
export const ADMIN_CONFIG = {
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

type ConfigState = Omit<typeof ADMIN_CONFIG, 'loginMethods'> & {
  loginMethods: Omit<typeof ADMIN_CONFIG['loginMethods'], 'otpChannels'> & {
    otpChannels: Record<string, { enabled: boolean; priority: number; devOnly: boolean }>;
  };
};

/** Mutable config state for P1-5a2 editor tests - fresh copy via resetConfig(). */
let configState: ConfigState = JSON.parse(JSON.stringify(ADMIN_CONFIG));

export function resetConfig(): void {
  configState = JSON.parse(JSON.stringify(ADMIN_CONFIG));
}

/** Last PATCH body seen by the config route (tests assert the changed-fields-only contract). */
let lastConfigPatch: Record<string, unknown> | null = null;

export function getLastConfigPatch(): Record<string, unknown> | null {
  return lastConfigPatch;
}

const json = (route: Route, status: number, body: unknown): Promise<void> =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/**
 * Installs route mocks for every /v1/** call the admin shell makes.
 * `role` controls the identity returned by login / refresh + /v1/me.
 */
export async function mockApi(page: Page, role: string): Promise<void> {
  const user: MockUser = { id: 'u1', phone: '15550001111', email: null, displayName: 'Ops Admin', role, status: 'ACTIVE', locale: 'en', createdAtEpochSec: 1737600000 };

  await page.route('**/v1/auth/login/password', (route) =>
    json(route, 200, { user, tokens: TOKENS, created: false }));
  await page.route('**/v1/auth/refresh', (route) => json(route, 200, { tokens: TOKENS }));
  await page.route('**/v1/auth/logout', (route) => route.fulfill({ status: 204 }));
  await page.route('**/v1/me', (route) => json(route, 200, user));
  await page.route('**/v1/admin/users?*', (route) =>
    json(route, 200, { users: USERS, limit: 20, offset: 0 }));
  await page.route('**/v1/admin/audit?*', (route) =>
    json(route, 200, { verifyOk: true, events: AUDIT_EVENTS }));
  await page.route('**/v1/admin/config/login-methods', (route) => {
    if (route.request().method() !== 'PATCH') return json(route, 200, configState);
    // P1-5a2 PATCH: mirror the backend contract (ConfigService.updateLoginMethods).
    const body = route.request().postDataJSON() as {
      passwordLoginEnabled?: boolean;
      otpAutoRegister?: boolean;
      otpChannels?: Record<string, { enabled?: boolean; priority?: number }>;
    };
    lastConfigPatch = body as Record<string, unknown>;
    const hasPatchField = [body.passwordLoginEnabled, body.otpAutoRegister].some((v) => typeof v === 'boolean')
      || (body.otpChannels !== undefined && Object.keys(body.otpChannels).length > 0);
    if (!hasPatchField) {
      return json(route, 400, { code: 'empty_patch', message: 'Validation failed: patch', traceId: null });
    }
    for (const name of Object.keys(body.otpChannels ?? {})) {
      if (!['SMS', 'TELEGRAM', 'EMAIL', 'WHATSAPP'].includes(name)) {
        return json(route, 400, { code: 'unknown_channel', message: `Validation failed: otpChannels.${name}`, traceId: null });
      }
    }
    // Apply the patch to the fixture state, then enforce distinct priorities across ENABLED channels.
    const next = JSON.parse(JSON.stringify(configState)) as ConfigState;
    if (body.passwordLoginEnabled !== undefined) next.loginMethods.passwordLoginEnabled = body.passwordLoginEnabled;
    if (body.otpAutoRegister !== undefined) next.loginMethods.otpAutoRegister = body.otpAutoRegister;
    for (const [name, ch] of Object.entries(body.otpChannels ?? {})) {
      const old = next.loginMethods.otpChannels[name];
      if (!old) continue;
      next.loginMethods.otpChannels[name] = {
        enabled: ch.enabled ?? old.enabled,
        priority: ch.priority ?? old.priority,
        devOnly: old.devOnly,
      };
    }
    const enabledPriorities = Object.values(next.loginMethods.otpChannels)
      .filter((ch) => ch.enabled)
      .map((ch) => ch.priority);
    if (new Set(enabledPriorities).size !== enabledPriorities.length) {
      return json(route, 400, { code: 'duplicate_priority', message: 'Validation failed: otpChannels.priority', traceId: null });
    }
    configState = next;
    configState.currentVersion += 1;
    const entry = {
      version: configState.currentVersion,
      issuedAtEpochSec: 1737620000,
      config: '{"loginMethods":{}}',
      signature: `sig${configState.currentVersion}`,
      changedBy: 'u1',
      change: 'login_methods_updated',
    };
    configState.history.unshift(entry);
    return json(route, 200, entry);
  });

  // P1-4b mutations. The created user joins the list (id u4); PATCH rewrites the row;
  // reset-password/force-logout answer the documented shapes.
  await page.route('**/v1/admin/users', (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    if (USERS.some((u) => u.phone === body.phone || (body.email && u.email === body.email))) {
      return json(route, 409, { code: 'identifier_taken', message: 'Phone or email already registered', traceId: null });
    }
    const created: MockUser = {
      id: 'u4',
      phone: (body.phone as string) ?? null,
      email: (body.email as string) ?? null,
      displayName: body.displayName as string,
      role: body.role as string,
      status: 'ACTIVE',
      locale: (body.locale as string) ?? 'en',
      createdAtEpochSec: 1737700000,
    };
    USERS.push(created);
    return json(route, 201, created);
  });
  await page.route('**/v1/admin/users/*/reset-password', (route) => {
    const id = route.request().url().match(/\/v1\/admin\/users\/([^/]+)\/reset-password/)?.[1];
    const u = USERS.find((x) => x.id === id);
    if (!u) return json(route, 404, { code: 'not_found', message: 'User not found', traceId: null });
    return json(route, 200, { ...u, id: u.id });
  });
  await page.route('**/v1/admin/users/*/force-logout', (route) => {
    const id = route.request().url().match(/\/v1\/admin\/users\/([^/]+)\/force-logout/)?.[1];
    const u = USERS.find((x) => x.id === id);
    if (!u) return json(route, 404, { code: 'not_found', message: 'User not found', traceId: null });
    if (id === 'u1') return json(route, 400, { code: 'self_force_logout', message: 'Cannot force-logout yourself', traceId: null });
    return json(route, 200, { revokedSessions: 2 });
  });
  await page.route('**/v1/admin/users/*', (route) => {
    const id = route.request().url().match(/\/v1\/admin\/users\/([^/]+)$/)?.[1];
    const u = USERS.find((x) => x.id === id);
    if (!u) return json(route, 404, { code: 'not_found', message: 'User not found', traceId: null });
    const body = route.request().postDataJSON() as Record<string, unknown>;
    const updated = {
      ...u,
      ...(body.displayName !== undefined ? { displayName: body.displayName as string } : {}),
      ...(body.locale !== undefined ? { locale: body.locale as string } : {}),
      ...(body.status !== undefined ? { status: body.status as string } : {}),
      ...(body.role !== undefined ? { role: body.role as string } : {}),
    } as MockUser;
    Object.assign(u, updated);
    return json(route, 200, updated);
  });

  // P6-1 verification center: queue + review transitions (mirror the backend contract).
  await page.route('**/v1/admin/verification?*', (route) => {
    const url = new URL(route.request().url());
    const statusFilter = url.searchParams.get('status');
    const rows = VERIFICATION_SUBMISSIONS.filter((s) => !statusFilter || s.status === statusFilter);
    return json(route, 200, { submissions: rows, limit: 25, offset: 0 });
  });
  await page.route(/v1\/admin\/verification\/([0-9a-f-]+)\/(approve|reject)$/, (route) => {
    const url = route.request().url();
    const id = url.match(/([0-9a-f-]{36})/)![1];
    const kind = url.endsWith('/approve') ? 'approve' : 'reject';
    const s = VERIFICATION_SUBMISSIONS.find((x) => x.id === id);
    if (!s) return json(route, 404, { code: 'submission_not_found', message: 'No such verification submission', traceId: null });
    if (s.status !== 'PENDING') return json(route, 409, { code: 'not_pending', message: 'Only PENDING submissions can be reviewed', traceId: null });
    const body = (route.request().postDataJSON() ?? {}) as { reason?: string };
    if (kind === 'reject' && !(body.reason ?? '').trim()) {
      return json(route, 400, { code: 'invalid_reason', message: 'A reject reason (1..512 chars) is required', traceId: null });
    }
    s.status = kind === 'approve' ? 'APPROVED' : 'REJECTED';
    s.reviewedAtEpochSec = 1764200900;
    s.reviewerId = 'u1';
    s.rejectReason = kind === 'reject' ? (body.reason ?? '').trim() : null;
    return json(route, 200, s);
  });
}

// ---- P6-1 verification center fixtures (mutable like USERS) ----
const VERIFICATION_SUBMISSIONS = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    userId: 'driver-1',
    licenseNumber: 'FR-12345678',
    vehiclePlate: 'AA-123-BB',
    declaredDocs: ['license_front', 'insurance'],
    status: 'PENDING',
    submittedAtEpochSec: 1764200000,
    reviewedAtEpochSec: null,
    reviewerId: null,
    rejectReason: null,
  },
  {
    id: '22222222-2222-2222-2222-222222222222',
    userId: 'driver-2',
    licenseNumber: 'FR-87654321',
    vehiclePlate: 'BB-456-CC',
    declaredDocs: ['license_front'],
    status: 'REJECTED',
    submittedAtEpochSec: 1764100000,
    reviewedAtEpochSec: 1764100500,
    reviewerId: 'u1',
    rejectReason: 'license unreadable',
  },
];

export function resetVerification(): void {
  VERIFICATION_SUBMISSIONS.length = 0;
  VERIFICATION_SUBMISSIONS.push(
    {
      id: '11111111-1111-1111-1111-111111111111',
      userId: 'driver-1',
      licenseNumber: 'FR-12345678',
      vehiclePlate: 'AA-123-BB',
      declaredDocs: ['license_front', 'insurance'],
      status: 'PENDING',
      submittedAtEpochSec: 1764200000,
      reviewedAtEpochSec: null,
      reviewerId: null,
      rejectReason: null,
    },
    {
      id: '22222222-2222-2222-2222-222222222222',
      userId: 'driver-2',
      licenseNumber: 'FR-87654321',
      vehiclePlate: 'BB-456-CC',
      declaredDocs: ['license_front'],
      status: 'REJECTED',
      submittedAtEpochSec: 1764100000,
      reviewedAtEpochSec: 1764100500,
      reviewerId: 'u1',
      rejectReason: 'license unreadable',
    },
  );
}

/** Seeds a session so the shell boots straight into the authenticated area. */
export async function seedSession(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem('admin.refreshToken', 'seeded-refresh');
    sessionStorage.setItem('admin.accessToken', 'seeded-access');
  });
}

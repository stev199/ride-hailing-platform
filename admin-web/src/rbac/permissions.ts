/**
 * Client-side mirror of backend/src/main/kotlin/com/ridehailing/backend/rbac/Rbac.kt
 * (P1-3 matrix). This is UX ONLY: it hides/disables nav and actions the server would
 * 403 anyway - the backend (AdminRoutes.withPermission) is the single authority.
 *
 * PARITY RULE: any change here must be mirrored in Rbac.kt in the same task, and the
 * fixture in rbac/test/rbac-fixture.ts re-pinned (test fails loudly on drift by design).
 */
import type { Role } from '../api/types';

export enum Perm {
  USERS_READ = 'USERS_READ',
  USERS_WRITE = 'USERS_WRITE',
  USERS_BAN = 'USERS_BAN',
  USERS_RESET_PASSWORD = 'USERS_RESET_PASSWORD',
  SESSIONS_ADMIN = 'SESSIONS_ADMIN',
  AUDIT_READ = 'AUDIT_READ',
  CONFIG_READ = 'CONFIG_READ',
  CONFIG_WRITE = 'CONFIG_WRITE',
  MESH_READ = 'MESH_READ',
  MESH_ADMIN = 'MESH_ADMIN',
  MONEY_READ = 'MONEY_READ',
  MONEY_WRITE = 'MONEY_WRITE',
}

export const ALL_PERMS: Perm[] = [
  Perm.USERS_READ,
  Perm.USERS_WRITE,
  Perm.USERS_BAN,
  Perm.USERS_RESET_PASSWORD,
  Perm.SESSIONS_ADMIN,
  Perm.AUDIT_READ,
  Perm.CONFIG_READ,
  Perm.CONFIG_WRITE,
  Perm.MESH_READ,
  Perm.MESH_ADMIN,
  Perm.MONEY_READ,
  Perm.MONEY_WRITE,
];

const READ_ONLY: Perm[] = [
  Perm.USERS_READ,
  Perm.AUDIT_READ,
  Perm.CONFIG_READ,
  Perm.MESH_READ,
  Perm.MONEY_READ,
];

/** Byte-for-byte mirror of Rbac.kt BY_ROLE (see fixture test). */
export const PERMISSIONS_BY_ROLE: Record<Role, Perm[]> = {
  RIDER: [],
  DRIVER: [],
  ADMIN_SUPER: [...ALL_PERMS],
  ADMIN_OPS: [
    Perm.USERS_READ,
    Perm.USERS_WRITE,
    Perm.USERS_BAN,
    Perm.USERS_RESET_PASSWORD,
    Perm.SESSIONS_ADMIN,
    Perm.AUDIT_READ,
    Perm.CONFIG_READ,
    Perm.CONFIG_WRITE,
    Perm.MESH_READ,
    Perm.MESH_ADMIN,
    Perm.MONEY_READ,
  ],
  ADMIN_SUPPORT: [
    Perm.USERS_READ,
    Perm.USERS_RESET_PASSWORD,
    Perm.SESSIONS_ADMIN,
    Perm.AUDIT_READ,
    Perm.CONFIG_READ,
  ],
  ADMIN_FINANCE: [
    Perm.USERS_READ,
    Perm.AUDIT_READ,
    Perm.CONFIG_READ,
    Perm.MONEY_READ,
    Perm.MONEY_WRITE,
  ],
  ADMIN_VERIFIER: [Perm.USERS_READ, Perm.AUDIT_READ, Perm.CONFIG_READ],
  ADMIN_READONLY: [...READ_ONLY],
};

export function permissionsOf(role: Role): Perm[] {
  return PERMISSIONS_BY_ROLE[role] ?? [];
}

export function can(role: Role, perm: Perm): boolean {
  return permissionsOf(role).includes(perm);
}

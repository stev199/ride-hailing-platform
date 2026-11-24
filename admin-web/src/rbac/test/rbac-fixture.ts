/**
 * Pinned fixture: copy of Rbac.kt BY_ROLE at P1-3 commit 9db13be (verified on CI run
 * 45286635288). The parity test compares PERMISSIONS_BY_ROLE against THIS table - if
 * backend Rbac.kt changes, the drift test fails and both sides must be updated together.
 */
import type { Role } from '../../api/types';

export const RBAC_FIXTURE: Record<Role, string[]> = {
  RIDER: [],
  DRIVER: [],
  ADMIN_SUPER: [
    'USERS_READ', 'USERS_WRITE', 'USERS_BAN', 'USERS_RESET_PASSWORD',
    'SESSIONS_ADMIN', 'AUDIT_READ', 'CONFIG_READ', 'CONFIG_WRITE',
    'MESH_READ', 'MESH_ADMIN', 'MONEY_READ', 'MONEY_WRITE',
  ],
  ADMIN_OPS: [
    'USERS_READ', 'USERS_WRITE', 'USERS_BAN', 'USERS_RESET_PASSWORD',
    'SESSIONS_ADMIN', 'AUDIT_READ', 'CONFIG_READ', 'CONFIG_WRITE',
    'MESH_READ', 'MESH_ADMIN', 'MONEY_READ',
  ],
  ADMIN_SUPPORT: ['USERS_READ', 'USERS_RESET_PASSWORD', 'SESSIONS_ADMIN', 'AUDIT_READ', 'CONFIG_READ'],
  ADMIN_FINANCE: ['USERS_READ', 'AUDIT_READ', 'CONFIG_READ', 'MONEY_READ', 'MONEY_WRITE'],
  ADMIN_VERIFIER: ['USERS_READ', 'AUDIT_READ', 'CONFIG_READ'],
  ADMIN_READONLY: ['USERS_READ', 'AUDIT_READ', 'CONFIG_READ', 'MESH_READ', 'MONEY_READ'],
};

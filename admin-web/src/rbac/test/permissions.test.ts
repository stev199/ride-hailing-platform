import { describe, expect, it } from 'vitest';
import { ALL_PERMS, PERMISSIONS_BY_ROLE, can, permissionsOf, Perm } from '../permissions';
import { RBAC_FIXTURE } from './rbac-fixture';
import type { Role } from '../../api/types';

const ROLES: Role[] = [
  'RIDER', 'DRIVER', 'ADMIN_SUPER', 'ADMIN_OPS',
  'ADMIN_SUPPORT', 'ADMIN_FINANCE', 'ADMIN_VERIFIER', 'ADMIN_READONLY',
];

describe('RBAC client mirror parity (Rbac.kt)', () => {
  it('covers exactly the 8 roles x 16 perms of the backend matrix (P7-5a added TRIPS_WRITE)', () => {
    expect(Object.keys(PERMISSIONS_BY_ROLE).sort()).toEqual([...ROLES].sort());
    expect(ALL_PERMS).toHaveLength(16);
  });

  it('matches the pinned Rbac.kt fixture byte-for-byte (drift test)', () => {
    for (const role of ROLES) {
      expect(
        permissionsOf(role).map(String).sort(),
        `role ${role} drifted from Rbac.kt`,
      ).toEqual([...RBAC_FIXTURE[role]].sort());
    }
  });

  it('RIDER/DRIVER hold the empty set (driver blindness precursor)', () => {
    expect(permissionsOf('RIDER')).toEqual([]);
    expect(permissionsOf('DRIVER')).toEqual([]);
    expect(can('DRIVER', Perm.USERS_READ)).toBe(false);
  });

  it('ADMIN_SUPER holds every permission', () => {
    expect(permissionsOf('ADMIN_SUPER')).toEqual(ALL_PERMS);
  });

  it('unknown role is safe-empty (never crashes the UI)', () => {
    expect(permissionsOf('NONEXISTENT' as Role)).toEqual([]);
  });
});

package com.ridehailing.backend.rbac

import com.ridehailing.backend.auth.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-3 RBAC matrix tests. The spec's driver-blindness rule ("Driver tokens cannot
 * query mesh tables (RBAC tests)") is pinned here at the permission-model level;
 * the route-level 403 behavior is exercised in AdminRoutesTest.
 */
class RbacTest {

    @Test
    fun `riders and drivers hold no admin permissions at all`() {
        for (role in listOf(Role.RIDER, Role.DRIVER)) {
            assertEquals(emptySet(), Rbac.permissions(role), "$role must have zero permissions")
            for (perm in Perm.entries) {
                assertFalse(Rbac.can(role, perm), "$role must not have $perm")
            }
        }
    }

    @Test
    fun `super admin has every permission`() {
        assertEquals(Perm.entries.toSet(), Rbac.permissions(Role.ADMIN_SUPER))
    }

    @Test
    fun `readonly role sees but never writes`() {
        val perms = Rbac.permissions(Role.ADMIN_READONLY)
        assertTrue(perms.contains(Perm.USERS_READ) && perms.contains(Perm.AUDIT_READ))
        for (writer in listOf(Perm.USERS_WRITE, Perm.USERS_BAN, Perm.USERS_RESET_PASSWORD, Perm.CONFIG_WRITE, Perm.MONEY_WRITE)) {
            assertFalse(perms.contains(writer), "ADMIN_READONLY must not have $writer")
        }
    }

    @Test
    fun `ops manages users and config but cannot touch money writes`() {
        val perms = Rbac.permissions(Role.ADMIN_OPS)
        assertTrue(perms.contains(Perm.USERS_WRITE) && perms.contains(Perm.MESH_ADMIN))
        assertFalse(perms.contains(Perm.MONEY_WRITE), "ADMIN_OPS must not move money")
    }

    @Test
    fun `support resets passwords and manages sessions but cannot edit users`() {
        val perms = Rbac.permissions(Role.ADMIN_SUPPORT)
        assertTrue(perms.contains(Perm.USERS_RESET_PASSWORD) && perms.contains(Perm.SESSIONS_ADMIN))
        assertFalse(perms.contains(Perm.USERS_WRITE))
        assertFalse(perms.contains(Perm.USERS_BAN))
    }

    @Test
    fun `finance moves money but cannot write users`() {
        val perms = Rbac.permissions(Role.ADMIN_FINANCE)
        assertTrue(perms.contains(Perm.MONEY_WRITE) && perms.contains(Perm.MONEY_READ))
        assertFalse(perms.contains(Perm.USERS_WRITE))
    }

    @Test
    fun `audit read is admin-only across the role space`() {
        for (role in Role.entries) {
            assertEquals(role.isAdmin, Rbac.can(role, Perm.AUDIT_READ), "$role AUDIT_READ must match isAdmin")
        }
    }
}

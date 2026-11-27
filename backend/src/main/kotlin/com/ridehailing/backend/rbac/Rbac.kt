package com.ridehailing.backend.rbac

import com.ridehailing.backend.auth.Role

/**
 * Server-side RBAC (Part B hard constraint 6: "Admin: same REST API, server-side
 * RBAC"; spec Admin section roles: Super Admin, Operations, Support, Finance,
 * Verifier, Read-only, custom roles).
 *
 * One permission per admin capability; roles map to permission SETS (the seam for
 * custom roles later - a custom role is just another set). Enforcement lives in
 * AdminRoutes.withPermission: a valid token of the wrong role is 403 forbidden,
 * a missing/invalid token is 401 unauthorized (never merged - B-04).
 *
 * Driver blindness precursor (spec: "Driver tokens cannot query mesh tables
 * (RBAC tests)"): RIDER/DRIVER hold an EMPTY permission set - every admin route
 * (/v1/admin/...) is 403 for them by construction.
 */
enum class Perm {
    /** List/search/view any user. */
    USERS_READ,
    /** Create users, edit profile fields, assign roles. */
    USERS_WRITE,
    /** Ban/unban/suspend (destructive user status changes). */
    USERS_BAN,
    /** Reset a user's password. */
    USERS_RESET_PASSWORD,
    /** List live sessions of any user; force logout. */
    SESSIONS_ADMIN,
    /** Read the hash-chained audit/event log. */
    AUDIT_READ,
    /** Read runtime configuration (config center later, P1-5). */
    CONFIG_READ,
    /** Change runtime configuration. */
    CONFIG_WRITE,
    /** Read mesh admin tables (origin, hops, gateway, path). */
    MESH_READ,
    /** Mesh admin actions (revocation, force-offline). */
    MESH_ADMIN,
    /** Read money/ledger tables. */
    MONEY_READ,
    /** Manual credit/debit and payout approvals. */
    MONEY_WRITE,
    /** Read trips (admin list + detail + ride timeline). */
    TRIPS_READ,
}

object Rbac {

    private val READ_ONLY = setOf(
        Perm.USERS_READ, Perm.AUDIT_READ, Perm.CONFIG_READ, Perm.MESH_READ, Perm.MONEY_READ,
        Perm.TRIPS_READ,
    )

    private val BY_ROLE: Map<Role, Set<Perm>> = mapOf(
        // Riders/drivers: no admin permissions at all (driver blindness, spec).
        Role.RIDER to emptySet(),
        Role.DRIVER to emptySet(),
        Role.ADMIN_SUPER to Perm.entries.toSet(),
        Role.ADMIN_OPS to setOf(
            Perm.USERS_READ, Perm.USERS_WRITE, Perm.USERS_BAN, Perm.USERS_RESET_PASSWORD,
            Perm.SESSIONS_ADMIN, Perm.AUDIT_READ, Perm.CONFIG_READ, Perm.CONFIG_WRITE,
            Perm.MESH_READ, Perm.MESH_ADMIN, Perm.MONEY_READ, Perm.TRIPS_READ,
        ),
        Role.ADMIN_SUPPORT to setOf(
            Perm.USERS_READ, Perm.USERS_RESET_PASSWORD, Perm.SESSIONS_ADMIN, Perm.AUDIT_READ, Perm.CONFIG_READ,
            Perm.TRIPS_READ,
        ),
        Role.ADMIN_FINANCE to setOf(
            Perm.USERS_READ, Perm.AUDIT_READ, Perm.CONFIG_READ, Perm.MONEY_READ, Perm.MONEY_WRITE,
            Perm.TRIPS_READ,
        ),
        Role.ADMIN_VERIFIER to setOf(Perm.USERS_READ, Perm.AUDIT_READ, Perm.CONFIG_READ, Perm.TRIPS_READ),
        Role.ADMIN_READONLY to READ_ONLY,
    )

    /** Permissions granted to [role] (never null; unknown/empty safe). */
    fun permissions(role: Role): Set<Perm> = BY_ROLE[role] ?: emptySet()

    fun can(role: Role, perm: Perm): Boolean = permissions(role).contains(perm)
}

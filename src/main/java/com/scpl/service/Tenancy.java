package com.scpl.service;

import com.scpl.exception.TenantAccessException;
import com.scpl.model.User;

/**
 * Service-level tenant checks. TenantGuard (Phase 5) protects the controller edge; these
 * checks mean a service call is still safe if a controller forgets to use the guard.
 * The platform admin is deliberately rejected by requireAdminOf: it manages tenants,
 * it does not operate inside them.
 */
public final class Tenancy {

    private Tenancy() {
    }

    public static void requireAdminOf(User actor, Long societyId) {
        if (actor == null || !actor.isSocietyAdmin() || !Boolean.TRUE.equals(actor.getEnabled())
                || societyId == null || !societyId.equals(actor.getSocietyId())) {
            throw new TenantAccessException("You are not an admin of this society");
        }
    }

    public static void requirePlatformAdmin(User actor) {
        if (actor == null || !actor.isPlatformAdmin() || !Boolean.TRUE.equals(actor.getEnabled())) {
            throw new TenantAccessException("Platform admin only");
        }
    }
}
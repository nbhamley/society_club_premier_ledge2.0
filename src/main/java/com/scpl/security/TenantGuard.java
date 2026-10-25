package com.scpl.security;

import com.scpl.exception.TenantAccessException;
import com.scpl.model.SocietyScoped;
import com.scpl.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Controller-edge tenant check (§2): every controller method that touches a society-scoped entity calls
 * assertSameSociety first. A violation throws TenantAccessException (HTTP 403).
 *
 * <p>This is one of three layers. Services re-check with Tenancy.requireAdminOf, and repositories fetch
 * through findByIdAndSociety_Id, so a wrong-tenant id is empty even if a controller forgets the guard.
 * PLATFORM_ADMIN passes assertSameSociety (it manages tenants) but never passes canSeePlayerPii.
 */
@Service
@RequiredArgsConstructor
public class TenantGuard {

    private final CurrentUser current;

    public void assertSameSociety(Long societyId) {
        User u = current.require();
        if (u.isPlatformAdmin()) {
            return;
        }
        if (societyId == null || !societyId.equals(u.getSocietyId())) {
            throw new TenantAccessException("You do not have access to this society");
        }
    }

    public void assertSameSociety(SocietyScoped entity) {
        assertSameSociety(entity == null ? null : entity.getSocietyId());
    }

    /** The signed-in society admin; the platform admin is not one, because it never operates inside a tenant. */
    public User requireSocietyAdmin() {
        User u = current.require();
        if (!u.isSocietyAdmin() || u.getSocietyId() == null) {
            throw new TenantAccessException("Society admins only");
        }
        return u;
    }

    /**
     * For /society-admin routes: returns the admin's own society id. A request that names a different
     * society (for example ?societyId=7) is refused, which is what makes "opening another society's
     * /society-admin" a 403 (acceptance test 5).
     */
    public Long societyIdForAdmin(Long requestedOrNull) {
        User u = requireSocietyAdmin();
        if (requestedOrNull != null && !requestedOrNull.equals(u.getSocietyId())) {
            throw new TenantAccessException("You do not have access to this society");
        }
        return u.getSocietyId();
    }

    public User requirePlatformAdmin() {
        User u = current.require();
        if (!u.isPlatformAdmin()) {
            throw new TenantAccessException("Platform admins only");
        }
        return u;
    }

    /**
     * The §14 rule: a player's contact details are visible to that player and to admins of the player's
     * own society, and to nobody else (not other societies, not the platform admin, not anonymous users).
     */
    public boolean canSeePlayerPii(Long playerSocietyId, Long playerId) {
        User u = current.orNull();
        if (u == null) {
            return false;
        }
        if (u.isSocietyAdmin() && playerSocietyId != null && playerSocietyId.equals(u.getSocietyId())) {
            return true;
        }
        return u.getPlayerId() != null && u.getPlayerId().equals(playerId);
    }
}
package com.scpl.web;

import com.scpl.model.User;
import com.scpl.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The first line of every /society-admin handler (§2). societyIdForAdmin refuses a request that names a different
 * society (acceptance test 5) and assertSameSociety is then the literal tenant check. The society id used afterwards
 * always comes from here, never straight from a request parameter.
 */
@Component
@RequiredArgsConstructor
public class AdminAccess {

    public record Ctx(User actor, Long societyId) {
    }

    private final TenantGuard guard;

    public Ctx ctx() {
        return ctx(null);
    }

    public Ctx ctx(Long requestedSocietyId) {
        Long societyId = guard.societyIdForAdmin(requestedSocietyId);
        guard.assertSameSociety(societyId);
        return new Ctx(guard.requireSocietyAdmin(), societyId);
    }
}
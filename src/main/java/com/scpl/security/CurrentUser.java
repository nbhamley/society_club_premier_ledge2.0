package com.scpl.security;

import com.scpl.exception.TenantAccessException;
import com.scpl.model.User;
import com.scpl.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The signed-in user as a fresh database entity (society and player already loaded), cached for the
 * duration of one request. Reading it anew each request means a disabled account, a changed role or a
 * removed admin takes effect immediately instead of when the session expires.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private static final String CACHE = CurrentUser.class.getName() + ".USER";

    /** Distinguishes "not looked up yet" from "looked up, nobody". */
    private record Cached(User user) {
    }

    private final UserRepository users;

    public ScplUserDetails principalOrNull() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getPrincipal() instanceof ScplUserDetails p ? p : null;
    }

    /** Null when anonymous, or when the account no longer exists or has been disabled. */
    public User orNull() {
        ScplUserDetails p = principalOrNull();
        if (p == null) {
            return null;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST) instanceof Cached c) {
            return c.user();
        }
        User fresh = users.findByUsernameIgnoreCase(p.getUsername())
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()) && u.getId().equals(p.getId()))
                .orElse(null);
        if (attrs != null) {
            attrs.setAttribute(CACHE, new Cached(fresh), RequestAttributes.SCOPE_REQUEST);
        }
        return fresh;
    }

    public User require() {
        User u = orNull();
        if (u == null) {
            throw new TenantAccessException("You need to sign in with an active account");
        }
        return u;
    }

    /** Null for anonymous users and for platform admins (who belong to no society). */
    public Long societyIdOrNull() {
        User u = orNull();
        return u == null ? null : u.getSocietyId();
    }
}
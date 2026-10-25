package com.scpl.security;

import com.scpl.exception.ReadOnlyModeException;
import com.scpl.model.User;
import com.scpl.repo.SocietyRepository;
import com.scpl.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks every mutating request for a read-only society (§6, acceptance test 14). Read-only means
 * PAST_DUE beyond the grace period, CANCELLED, or SUSPENDED; SubscriptionService.isReadOnly decides.
 *
 * <p>Exempt, because a locked society must still be able to recover and leave (§10):
 * billing (pay), backup (the final export), profile (own password) and logout.
 * Platform admins are never blocked: they belong to no society.
 */
@Component
@RequiredArgsConstructor
public class ReadOnlyGuard implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final List<String> EXEMPT = List.of("/society-admin/billing", "/society-admin/backup",
            "/profile", "/logout", "/login");
    /** Public sign-up form: the target society comes from the URL, not from a login. */
    private static final Pattern PUBLIC_REGISTER = Pattern.compile("^/society/([^/]+)/register$");

    private final CurrentUser current;
    private final SubscriptionService subscriptions;
    private final SocietyRepository societies;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (EXEMPT.stream().anyMatch(prefix -> isUnder(path, prefix))) {
            return true;
        }
        Long societyId = societyToCheck(path);
        if (societyId != null && subscriptions.isReadOnly(societyId)) {
            throw new ReadOnlyModeException("This society is read-only because its subscription needs attention. "
                    + "You can still view everything, pay on the billing page, and download a backup.");
        }
        return true;
    }

    private Long societyToCheck(String path) {
        Matcher m = PUBLIC_REGISTER.matcher(path);
        if (m.matches()) {
            return societies.findBySlug(m.group(1)).map(s -> s.getId()).orElse(null);
        }
        User u = current.orNull();
        return u == null || u.isPlatformAdmin() ? null : u.getSocietyId();
    }

    /** "/society-admin/billing" matches itself and its sub-paths, but not "/society-admin/billingx". */
    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
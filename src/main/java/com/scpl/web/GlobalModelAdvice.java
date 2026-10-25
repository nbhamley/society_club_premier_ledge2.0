package com.scpl.web;

import com.scpl.dto.AccountViews.NavView;
import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.AdminApprovalService;
import com.scpl.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the "nav" model into every HTML page: display name, role, society, the "Playing as" chip (§6),
 * the notification bell count and how many approvals wait for this admin's vote.
 * Null for anonymous visitors, so layouts must test ${nav != null}. Skipped for JSON endpoints.
 */
@ControllerAdvice(basePackages = "com.scpl.web")
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalModelAdvice.class);

    private final CurrentUser current;
    private final NotificationService notifications;
    private final AdminApprovalService approvals;

    @ModelAttribute("nav")
    public NavView navAttribute(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/") ? null : nav();
    }

    /** Also called by GlobalExceptionHandler, since @ModelAttribute methods do not run for error views. */
    public NavView nav() {
        try {
            User u = current.orNull();
            if (u == null) {
                return null;
            }
            long unread = notifications.unreadCount(u.getId());
            long votes = u.isSocietyAdmin() ? approvals.awaitingVoteFrom(u).size() : 0;
            return new NavView(u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername(),
                    u.getRole().getLabel(), u.isPlatformAdmin(), u.isSocietyAdmin(),
                    u.getSociety() == null ? null : u.getSociety().getName(),
                    u.getSociety() == null ? null : u.getSociety().getSlug(),
                    u.getPlayer() == null ? null : u.getPlayer().getName(),
                    u.isSocietyAdmin() && u.getPlayer() == null, unread, votes);
        } catch (RuntimeException e) {
            // The header is decoration: a failure here must never turn a page (or an error page) into a 500.
            log.warn("Could not build nav model: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
package com.scpl.web;

import com.scpl.dto.PublicViews;
import com.scpl.model.AdminApproval;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.SocietyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.net.URI;
import java.util.Locale;

/** Small helpers shared by controllers. Kept free of business rules: those live in the services. */
@Component
@RequiredArgsConstructor
public class WebSupport {

    public static final String FLASH_ERROR = "flashError";
    public static final String FLASH_SUCCESS = "flashSuccess";

    private final CurrentUser current;
    private final SocietyService societies;

    /** Header card for the public society sub-pages. getPublicBySlug hides DRAFT and SUSPENDED societies. */
    public PublicViews.SocietyCard societyCard(String slug) {
        Society s = societies.getPublicBySlug(slug);
        return new PublicViews.SocietyCard(s.getName(), s.getSlug(), s.getCity(), s.getLogoUrl(), s.getDescription());
    }

    /** Whether to render the share row. Only a UI hint: SocialShareService enforces ownership. */
    public boolean canShare(String societySlug) {
        User u = current.orNull();
        if (u == null || u.getSociety() == null || societySlug == null) {
            return false;
        }
        return societySlug.equals(u.getSociety().getSlug()) && (u.isSocietyAdmin() || u.getPlayerId() != null);
    }

    /** Same-origin path (plus query) from the Referer header; anything off-site falls back, so no open redirect. */
    public String backTo(HttpServletRequest request, String fallback) {
        String ref = request.getHeader("Referer");
        if (ref == null || ref.isBlank()) {
            return fallback;
        }
        try {
            URI uri = URI.create(ref);
            if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(request.getServerName())) {
                return fallback;
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return fallback;
            }
            String ctx = request.getContextPath();
            if (!ctx.isEmpty()) {
                if (!path.startsWith(ctx)) {
                    return fallback;
                }
                path = path.substring(ctx.length());
                if (path.isEmpty()) {
                    path = "/";
                }
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** JSON for API paths and fetch/XHR calls; HTML otherwise. */
    public static boolean wantsJson(HttpServletRequest r) {
        String path = r.getRequestURI().substring(r.getContextPath().length());
        if (path.startsWith("/api/")) {
            return true;
        }
        if ("XMLHttpRequest".equalsIgnoreCase(r.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = r.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    /** First validation problem as one readable sentence, for flash messages. */
    public static String firstError(BindingResult binding) {
        return binding.getAllErrors().stream().findFirst().map(e -> {
            if (e instanceof FieldError fe) {
                return fe.getField().replaceAll("\\[\\d+\\]", "") + " " + fe.getDefaultMessage();
            }
            return e.getDefaultMessage();
        }).orElse("Please check the form and try again");
    }

    /** Flash text for an action that was sent to the admin team for a vote instead of being carried out. */
    public static String approvalRaised(String what, AdminApproval a) {
        int required = a.getApprovalsRequired();
        String needs = required == 1 ? "approval from one other admin"
                : "approvals from " + required + " different admins";
        return what + " It needs " + needs + " before it takes effect. Track it under Approvals.";
    }
}
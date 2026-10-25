package com.scpl.security;

import com.scpl.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Feeds the login throttle and records User.lastLoginAt. */
@Component
@RequiredArgsConstructor
public class LoginEvents {

    private final UserRepository users;
    private final LoginThrottle throttle;

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        throttle.failure(keyOf(event.getAuthentication()));
    }

    /** Transactional because touchLastLogin is a @Modifying query, which needs one. */
    @EventListener
    @Transactional
    public void onLogin(InteractiveAuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        throttle.success(keyOf(auth));
        if (auth.getPrincipal() instanceof ScplUserDetails p) {
            users.touchLastLogin(p.getId(), LocalDateTime.now());
        }
    }

    private static String keyOf(Authentication auth) {
        String ip = auth.getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : null;
        return LoginThrottle.key(ip, auth.getName());
    }
}
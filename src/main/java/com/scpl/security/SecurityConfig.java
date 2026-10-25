package com.scpl.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * URL-level rules (the first gate; TenantGuard and Tenancy are the real tenant checks).
 * Default is deny: any URL not listed requires a login. CSRF protection stays on everywhere,
 * including /api/v1: fetch calls from admin.js must send the token as the X-CSRF-TOKEN header.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Templates must not contain inline scripts or inline event handlers (data goes in data-* attributes,
     * code in /js/admin.js). Chart.js is the only third-party script, from a pinned CDN URL.
     * img-src allows any https host because admins paste logo and photo URLs.
     */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: https:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottle throttle,
                                            AccessDeniedHandler deniedHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico",
                                "/error", "/error/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/about", "/subscribe", "/login", "/matches/*",
                                "/share/*", "/society/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/subscribe", "/society/*/register").permitAll()
                        .requestMatchers("/society-admin/**", "/admin", "/admin/**").hasRole("SOCIETY_ADMIN")
                        .requestMatchers("/api/v1/imports/**").hasRole("SOCIETY_ADMIN")
                        .requestMatchers("/platform-admin/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/me/**").hasAnyRole("SOCIETY_PLAYER", "SOCIETY_ADMIN")
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/dashboard", false)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(e -> e
                        .accessDeniedHandler(deniedHandler)
                        // The JSON API answers 401 instead of redirecting to the HTML login page.
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**")))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 403 for everything: the JSON API gets a JSON body, pages get forbidden.html through /error/403.
     * Phase 6's error controller must accept every HTTP method on /error/403, because a denied POST is
     * forwarded there with its original method.
     */
    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"forbidden\"}");
            } else {
                request.getRequestDispatcher("/error/403").forward(request, response);
            }
        };
    }

    /** Refuses a login attempt before any password check when the IP and username pair is locked out. */
    static final class LoginThrottleFilter extends OncePerRequestFilter {

        private final LoginThrottle throttle;

        LoginThrottleFilter(LoginThrottle throttle) {
            this.throttle = throttle;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !"POST".equals(request.getMethod())
                    || !request.getRequestURI().equals(request.getContextPath() + "/login");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String key = LoginThrottle.key(request.getRemoteAddr(), request.getParameter("username"));
            if (throttle.isBlocked(key)) {
                response.sendRedirect(request.getContextPath() + "/login?locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
package com.scpl.web;

import com.scpl.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The login form is rendered here; the POST is handled by Spring Security (SecurityConfig).
 * login.html reads ?error, ?locked and ?logout from the query string.
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final CurrentUser current;

    @GetMapping("/login")
    public String login() {
        return current.orNull() != null ? "redirect:/dashboard" : "login";
    }
}
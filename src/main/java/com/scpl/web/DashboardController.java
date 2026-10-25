package com.scpl.web;

import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Role-routed landing after sign-in: platform admins go to their console, everyone else sees their own society. */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final CurrentUser current;
    private final DashboardService dashboard;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        User u = current.require();
        if (u.isPlatformAdmin()) {
            return "redirect:/platform-admin";
        }
        model.addAttribute("dash", dashboard.forUser(u));
        return "dashboard";
    }
}
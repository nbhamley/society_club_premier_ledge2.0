package com.scpl.web;

import com.scpl.dto.DashboardViews.TeamHomeView;
import com.scpl.dto.PlayerProfileView;
import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.DashboardService;
import com.scpl.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * /me/* pages (§7). /me/player is the only page where a player's own PII is rendered outside the admin console;
 * PlayerService decides that from the user's own player id, never from a URL parameter. Admins who are also
 * players see their player profile here too (dual role, §6). Both pages work when nothing is linked:
 * the template then shows a "not registered yet" message.
 */
@Controller
@RequestMapping("/me")
@RequiredArgsConstructor
public class PlayerSelfController {

    private final CurrentUser current;
    private final PlayerService players;
    private final DashboardService dashboard;

    @GetMapping("/player")
    public String player(Model model) {
        User u = current.require();
        PlayerProfileView profile = u.getPlayerId() == null ? null : players.ownProfile(u);
        model.addAttribute("player", profile);
        model.addAttribute("societySlug", u.getSociety() == null ? null : u.getSociety().getSlug());
        model.addAttribute("canShare", profile != null);
        return "player-profile";
    }

    @GetMapping("/team")
    public String team(Model model) {
        User u = current.require();
        TeamHomeView team = dashboard.teamHome(u);
        model.addAttribute("team", team);
        model.addAttribute("societySlug", u.getSociety() == null ? null : u.getSociety().getSlug());
        model.addAttribute("canShare", team != null);
        return "team-home";
    }
}
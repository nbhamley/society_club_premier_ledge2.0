package com.scpl.web;

import com.scpl.dto.PlayerRequest;
import com.scpl.dto.SeasonRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.dto.TeamRequest;
import com.scpl.dto.AdminViews;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.AdminApproval;
import com.scpl.model.SeasonStatus;
import com.scpl.service.AdminConsoleService;
import com.scpl.service.PlayerService;
import com.scpl.service.RegistrationService;
import com.scpl.service.SeasonService;
import com.scpl.service.SocietyService;
import com.scpl.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

/**
 * The society admin console (§8): overview, seasons, teams, players, registrations and settings. Every handler
 * starts with access.ctx(), which is the tenant check from §2. Sensitive operations only raise an approval.
 * Validation and rule errors are handled by GlobalExceptionHandler (flash + redirect back).
 */
@Controller
@RequestMapping("/society-admin")
@RequiredArgsConstructor
public class SocietyAdminController {

    private final AdminAccess access;
    private final AdminConsoleService views;
    private final SeasonService seasons;
    private final TeamService teams;
    private final PlayerService players;
    private final RegistrationService registrations;
    private final SocietyService societies;

    @GetMapping
    public String console(@RequestParam(required = false) Long societyId, @RequestParam(required = false) Long season,
                          Model model) {
        AdminAccess.Ctx c = access.ctx(societyId);
        model.addAttribute("console", views.console(c.actor(), c.societyId(), season));
        model.addAttribute("details", views.details(c.actor(), c.societyId()));
        model.addAttribute("opts", AdminViews.FormOptions.all());
        return "admin";
    }

    @PostMapping("/settings")
    public String settings(@Valid @ModelAttribute SocietyRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        societies.updateDetails(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Society details saved.");
        return to("overview");
    }

    // ------------------------------------------------------------------ seasons

    @PostMapping("/seasons")
    public String createSeason(@Valid @ModelAttribute SeasonRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        seasons.create(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Season created.");
        return to("seasons");
    }

    @PostMapping("/seasons/{id}")
    public String updateSeason(@PathVariable Long id, @Valid @ModelAttribute SeasonRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        seasons.update(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Season saved.");
        return to("seasons");
    }

    @PostMapping("/seasons/{id}/status")
    public String seasonStatus(@PathVariable Long id, @RequestParam SeasonStatus status, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        seasons.setStatus(c.actor(), c.societyId(), id, status);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Season is now " + status.getLabel() + ".");
        return to("seasons");
    }

    @PostMapping("/seasons/{id}/delete")
    public String deleteSeason(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        AdminApproval a = seasons.requestDelete(c.actor(), c.societyId(), id, reason);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, WebSupport.approvalRaised("Season deletion requested.", a));
        return to("seasons");
    }

    // ------------------------------------------------------------------ teams

    @PostMapping("/teams")
    public String createTeam(@RequestParam Long seasonId, @Valid @ModelAttribute TeamRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        teams.create(c.actor(), c.societyId(), seasonId, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Team created.");
        return to("teams");
    }

    @PostMapping("/teams/{id}")
    public String updateTeam(@PathVariable Long id, @Valid @ModelAttribute TeamRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        teams.update(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Team saved.");
        return to("teams");
    }

    @PostMapping("/teams/{id}/captain")
    public String captain(@PathVariable Long id, @RequestParam(required = false) Long playerId, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        teams.setCaptain(c.actor(), c.societyId(), id, playerId);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, playerId == null ? "Captain cleared." : "Captain set.");
        return to("teams");
    }

    @PostMapping("/teams/{id}/delete")
    public String deleteTeam(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        teams.delete(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Team deleted.");
        return to("teams");
    }

    // ------------------------------------------------------------------ players

    @PostMapping("/players")
    public String createPlayer(@Valid @ModelAttribute PlayerRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        players.create(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Player added.");
        return to("players");
    }

    @PostMapping("/players/{id}")
    public String updatePlayer(@PathVariable Long id, @Valid @ModelAttribute PlayerRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        players.update(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Player saved.");
        return to("players");
    }

    /** Bulk action from the players table: assign the ticked players to a team, or release them when no team is chosen. */
    @PostMapping("/players/bulk-assign")
    public String bulkAssign(@RequestParam(name = "ids", required = false) List<Long> ids,
                             @RequestParam(required = false) Long teamId, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        requireSelection(ids, "player");
        int n = players.bulkAssignTeam(c.actor(), c.societyId(), ids, teamId);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, n + " player(s) updated.");
        return to("players");
    }

    @PostMapping("/players/{id}/drop")
    public String dropPlayer(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        AdminApproval a = players.requestDrop(c.actor(), c.societyId(), id, reason);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, WebSupport.approvalRaised("Player drop requested.", a));
        return to("players");
    }

    // ------------------------------------------------------------------ registrations

    /** Backing call for the registrations table (§8): APPROVE raises ONE batch approval, REJECT applies directly. */
    @PostMapping("/registrations/bulk-action")
    public String bulkAction(@RequestParam(name = "ids", required = false) List<Long> ids, @RequestParam String action,
                             RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        requireSelection(ids, "registration");
        Optional<AdminApproval> a = registrations.bulkAction(c.actor(), c.societyId(), ids, action);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, a.map(x -> WebSupport.approvalRaised(x.getNotes() + ".", x))
                .orElse("Selected registrations rejected."));
        return to("registrations");
    }

    @PostMapping("/registrations/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        AdminApproval a = registrations.requestApproval(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, WebSupport.approvalRaised("Registration sent for approval.", a));
        return to("registrations");
    }

    @PostMapping("/registrations/{id}/reject")
    public String reject(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        registrations.reject(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Registration rejected.");
        return to("registrations");
    }

    // ------------------------------------------------------------------ helpers

    private static void requireSelection(List<Long> ids, String noun) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessRuleException("Tick at least one " + noun + " first");
        }
    }

    private static String to(String tab) {
        return "redirect:/society-admin#" + tab;
    }
}
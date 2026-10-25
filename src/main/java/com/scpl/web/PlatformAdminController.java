package com.scpl.web;

import com.scpl.dto.PlatformViews;
import com.scpl.dto.PlatformViews.EventRow;
import com.scpl.dto.PlatformEventRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.User;
import com.scpl.security.TenantGuard;
import com.scpl.service.ContentService;
import com.scpl.service.CrossSocietyTournamentService;
import com.scpl.service.DumpStagingService;
import com.scpl.service.PlatformAdminService;
import com.scpl.service.RestoreService;
import com.scpl.service.SocietyService;
import com.scpl.service.SubscriptionService;
import com.scpl.web.form.AdminForms;
import com.scpl.web.form.PlatformSocietyForm;
import com.scpl.web.form.RestoreForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;

/**
 * Platform console (§7). Every handler starts with guard.requirePlatformAdmin(), the platform equivalent of the tenant
 * check. Platform admins manage tenants but never see player data: the models here (PlatformViews) have no PII fields.
 * Society creation, status changes, subscription overrides and restores are all audited by the services they call.
 */
@Controller
@RequestMapping("/platform-admin")
@RequiredArgsConstructor
public class PlatformAdminController {

    private static final String SCPLENC = ".scplenc";

    private final TenantGuard guard;
    private final PlatformAdminService platform;
    private final SocietyService societies;
    private final SubscriptionService subscriptions;
    private final ContentService content;
    private final CrossSocietyTournamentService tournaments;
    private final RestoreService restore;
    private final DumpStagingService dumps;

    @GetMapping
    public String home(Model model) {
        User u = guard.requirePlatformAdmin();
        model.addAttribute("dash", platform.dashboard(u));
        model.addAttribute("dumps", dumps.list(u));
        model.addAttribute("restoreForm", new RestoreForm());
        return "platform-admin";
    }

    // ------------------------------------------------------------------ societies

    @GetMapping("/societies")
    public String societiesPage(Model model) {
        User u = guard.requirePlatformAdmin();
        model.addAttribute("societies", platform.societies(u));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new PlatformSocietyForm());
        }
        return "platform-societies";
    }

    @PostMapping("/societies")
    public String createSociety(@Valid @ModelAttribute("form") PlatformSocietyForm form, BindingResult binding,
                                RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        if (binding.hasErrors()) {
            flash.addFlashAttribute(WebSupport.FLASH_ERROR, WebSupport.firstError(binding));
            return "redirect:/platform-admin/societies";
        }
        PlatformAdminService.CreatedSociety created = platform.createSociety(u, form.toSocietyRequest(),
                AdminForms.toRequests(form.getAdmins()), form.isActivateNow());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Society '" + created.name() + "' created ("
                + created.statusLabel() + ") with a 14-day trial at /society/" + created.slug() + ".");
        return "redirect:/platform-admin/societies";
    }

    /** action=ACTIVATE or SUSPEND. Activation is refused by SocietyService when the society has fewer than 3 admins (§6). */
    @PostMapping("/societies/{id}/status")
    public String societyStatus(@PathVariable Long id, @RequestParam String action, RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        switch (action.toUpperCase(Locale.ROOT)) {
            case "ACTIVATE" -> {
                societies.activate(id, u);
                flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Society activated.");
            }
            case "SUSPEND" -> {
                societies.suspend(id, u);
                flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Society suspended. Its public pages are hidden and it is read-only.");
            }
            default -> throw new BusinessRuleException("Unknown action: " + action);
        }
        return "redirect:/platform-admin/societies";
    }

    // ------------------------------------------------------------------ subscriptions

    @GetMapping("/subscriptions")
    public String subscriptionsPage(Model model) {
        User u = guard.requirePlatformAdmin();
        model.addAttribute("subscriptions", platform.subscriptionRows(u));
        model.addAttribute("opts", PlatformViews.Options.all());
        return "platform-subscriptions";
    }

    @PostMapping("/subscriptions/{id}/status")
    public String subscriptionStatus(@PathVariable Long id, @RequestParam SubscriptionStatus status, RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        subscriptions.platformSetStatus(u, id, status);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Subscription set to " + status.getLabel() + ".");
        return "redirect:/platform-admin/subscriptions";
    }

    // ------------------------------------------------------------------ events

    @GetMapping("/events")
    public String eventsPage(Model model) {
        User u = guard.requirePlatformAdmin();
        java.util.List<EventRow> rows = platform.eventRows(u);
        model.addAttribute("events", rows);
        model.addAttribute("societyOptions", platform.societyOptions(u));
        model.addAttribute("opts", PlatformViews.Options.all());
        return "platform-events";
    }

    /** Create, or update when an id is in the path. Links and thumbnails are validated to http(s) in ContentService. */
    @PostMapping({"/events", "/events/{id}"})
    public String saveEvent(@PathVariable(required = false) Long id, @Valid @ModelAttribute PlatformEventRequest req,
                            RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        content.saveEvent(u, id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Event saved.");
        return "redirect:/platform-admin/events";
    }

    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable Long id, RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        content.deleteEvent(u, id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Event deleted.");
        return "redirect:/platform-admin/events";
    }

    // ------------------------------------------------------------------ tournaments (feature-flagged: 404 when off)

    @GetMapping("/tournaments")
    public String tournamentsPage(@RequestParam(required = false) Long roster, Model model) {
        User u = guard.requirePlatformAdmin();
        model.addAttribute("tournaments", tournaments.list(u));
        if (roster != null) {
            model.addAttribute("roster", tournaments.roster(u, roster));
            model.addAttribute("rosterTournamentId", roster);
        }
        return "platform-tournaments";
    }

    @PostMapping("/tournaments/{id}/cancel")
    public String cancelTournament(@PathVariable Long id, RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        tournaments.cancelAsPlatform(u, id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Tournament cancelled.");
        return "redirect:/platform-admin/tournaments";
    }

    // ------------------------------------------------------------------ restore

    /** Restore one society from a .scplenc file into a NEW society (never overwrites). */
    @PostMapping("/restore")
    public String restoreSociety(@Valid @ModelAttribute("restoreForm") RestoreForm form, BindingResult binding,
                                 RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        if (binding.hasErrors()) {
            flash.addFlashAttribute(WebSupport.FLASH_ERROR, WebSupport.firstError(binding));
            return "redirect:/platform-admin";
        }
        MultipartFile file = form.getFile();
        String name = file == null || file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file == null || file.isEmpty() || !name.endsWith(SCPLENC)) {
            throw new BusinessRuleException("Choose a " + SCPLENC + " backup file");
        }
        RestoreService.Result r = restore.restore(u, file, form.getNewName(), AdminForms.toRequests(form.getAdmins()));
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Restored as a new society '" + r.name() + "' (draft, with a fresh trial). "
                + "Rows restored: " + r.counts() + ". Activate it when you are ready.");
        return "redirect:/platform-admin/societies";
    }

    /** Stages a full database dump for the maintenance window. It does not touch the live database (see DumpStagingService). */
    @PostMapping("/restore/dump")
    public String stageDump(@RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        User u = guard.requirePlatformAdmin();
        DumpStagingService.StagedDump d = dumps.stage(u, file);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Dump staged as " + d.fileName() + " (SHA-256 " + d.sha256()
                + "). It is not applied automatically: stop the app and replace the database during the maintenance window.");
        return "redirect:/platform-admin";
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/audit")
    public String audit(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Long societyId, Model model) {
        User u = guard.requirePlatformAdmin();
        model.addAttribute("audit", platform.auditPage(u, page, societyId));
        model.addAttribute("societyOptions", platform.societyOptions(u));
        return "platform-audit";
    }
}
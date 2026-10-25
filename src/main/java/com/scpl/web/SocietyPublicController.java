package com.scpl.web;

import com.scpl.dto.PlayerProfileView;
import com.scpl.dto.PublicViews;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Society;
import com.scpl.service.PublicSiteService;
import com.scpl.service.RegistrationService;
import com.scpl.service.SocietyService;
import com.scpl.web.form.RegistrationForm;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Public society pages (§7). The player pages are PII-free by construction: they always pass viewer = null,
 * so even the player or an admin sees stats only here. Their own details are on /me/player and in the console.
 * Unknown, DRAFT and SUSPENDED societies are all a 404.
 */
@Controller
@RequestMapping("/society/{slug}")
@RequiredArgsConstructor
public class SocietyPublicController {

    private final PublicSiteService site;
    private final SocietyService societies;
    private final RegistrationService registrations;
    private final WebSupport web;

    @GetMapping
    public String home(@PathVariable String slug, Model model) {
        model.addAttribute("home", site.societyHome(slug));
        return "society-home";
    }

    @GetMapping("/matches")
    public String matches(@PathVariable String slug, Model model) {
        model.addAttribute("society", web.societyCard(slug));
        model.addAttribute("matches", site.societyMatches(slug));
        return "society-matches";
    }

    @GetMapping("/seasons")
    public String seasons(@PathVariable String slug, Model model) {
        model.addAttribute("society", web.societyCard(slug));
        model.addAttribute("seasons", site.societySeasons(slug));
        return "society-seasons";
    }

    @GetMapping("/seasons/{id}")
    public String season(@PathVariable String slug, @PathVariable Long id, Model model) {
        PublicViews.SeasonDetailView detail = site.seasonDetail(slug, id);
        model.addAttribute("detail", detail);
        model.addAttribute("canShare", web.canShare(slug));
        return "society-season-detail";
    }

    @GetMapping("/players")
    public String players(@PathVariable String slug, Model model) {
        model.addAttribute("society", web.societyCard(slug));
        model.addAttribute("players", site.players(slug));
        return "society-players-public";
    }

    @GetMapping("/player/{id}")
    public String player(@PathVariable String slug, @PathVariable Long id, Model model) {
        PlayerProfileView profile = site.playerProfile(null, slug, id);
        model.addAttribute("society", web.societyCard(slug));
        model.addAttribute("player", profile);
        model.addAttribute("canShare", web.canShare(slug));
        return "society-player-public";
    }

    // ------------------------------------------------------------------ registration

    @GetMapping("/register")
    public String registerForm(@PathVariable String slug, @RequestParam(required = false) Long season, Model model) {
        RegistrationForm form = new RegistrationForm();
        form.setSeasonId(season);
        return registerPage(slug, model, form, null);
    }

    @PostMapping("/register")
    public String register(@PathVariable String slug, @Valid @ModelAttribute("form") RegistrationForm form,
                           BindingResult binding, Model model, RedirectAttributes flash) {
        Society society = societies.getPublicBySlug(slug); // 404 first for an unknown society
        if (form.getCompanyUrl() != null && !form.getCompanyUrl().isBlank()) {
            flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Thanks! Your registration was received.");
            return "redirect:/society/" + society.getSlug();
        }
        if (binding.hasErrors()) {
            return registerPage(slug, model, form, null);
        }
        try {
            registrations.submit(society.getId(), form.getSeasonId(), form.toRequest());
        } catch (BusinessRuleException | NotFoundException e) {
            return registerPage(slug, model, form, e.getMessage());
        }
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS,
                "Thanks! Your registration was received. The society's admins will review it.");
        return "redirect:/society/" + society.getSlug();
    }

    private String registerPage(String slug, Model model, RegistrationForm form, String error) {
        model.addAttribute("options", site.registerOptions(slug));
        model.addAttribute("form", form);
        if (error != null) {
            model.addAttribute(WebSupport.FLASH_ERROR, error);
        }
        return "register";
    }
}
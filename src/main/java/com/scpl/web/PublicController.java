package com.scpl.web;

import com.scpl.dto.PublicViews;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.SubscriptionPlan;
import com.scpl.service.PublicSiteService;
import com.scpl.service.SubscriptionService;
import com.scpl.web.form.SubscribeForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Platform-level public pages: home, about, pricing and sign-up, and the public match page. */
@Controller
@RequiredArgsConstructor
public class PublicController {

    private static final String SIGNUP_DONE = "Thanks! Your society has been created and your 14-day trial has started. "
            + "It will appear on the public site once a platform admin activates it. You can sign in now.";

    private final PublicSiteService site;
    private final SubscriptionService subscriptions;
    private final WebSupport web;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("home", site.platformHome());
        return "platform-home";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("plans", PublicViews.plans());
        return "about";
    }

    @GetMapping("/matches/{id}")
    public String match(@PathVariable Long id, Model model) {
        PublicViews.MatchDetailView detail = site.matchDetail(id);
        model.addAttribute("detail", detail);
        model.addAttribute("matchId", id);
        model.addAttribute("canShare", web.canShare(detail.societySlug()));
        return "match-detail";
    }

    // ------------------------------------------------------------------ subscribe

    @GetMapping("/subscribe")
    public String subscribeForm(@RequestParam(required = false) SubscriptionPlan plan, Model model) {
        SubscribeForm form = new SubscribeForm();
        form.setPlan(plan);
        return subscribePage(model, form, null);
    }

    @PostMapping("/subscribe")
    public String subscribe(@Valid @ModelAttribute("form") SubscribeForm form, BindingResult binding, Model model,
                            RedirectAttributes flash) {
        if (form.getCompanyUrl() != null && !form.getCompanyUrl().isBlank()) {
            // Honeypot tripped: look like success and do nothing.
            flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, SIGNUP_DONE);
            return "redirect:/login";
        }
        if (binding.hasErrors()) {
            return subscribePage(model, form, null);
        }
        try {
            subscriptions.signUp(form.toSocietyRequest(), form.toAdminRequests(), form.getPlan());
        } catch (BusinessRuleException e) {
            return subscribePage(model, form, e.getMessage());
        }
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, SIGNUP_DONE);
        return "redirect:/login";
    }

    private String subscribePage(Model model, SubscribeForm form, String error) {
        model.addAttribute("form", form);
        model.addAttribute("plans", PublicViews.plans());
        if (error != null) {
            model.addAttribute(WebSupport.FLASH_ERROR, error);
        }
        return "subscribe";
    }
}
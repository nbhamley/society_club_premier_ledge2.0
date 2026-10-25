package com.scpl.web;

import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.AccountService;
import com.scpl.web.form.PasswordForm;
import com.scpl.web.form.ProfileForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service profile (§7). Validation or business-rule errors flash back to /profile (the global handler
 * redirects to the Referer for service errors). /profile is exempt from the read-only guard so a locked society's
 * users can still change their password.
 */
@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final CurrentUser current;
    private final AccountService accounts;

    @GetMapping("/profile")
    public String view(Model model) {
        User u = current.require();
        model.addAttribute("profile", accounts.profileView(u));
        if (!model.containsAttribute("profileForm")) {
            model.addAttribute("profileForm", ProfileForm.from(u));
        }
        if (!model.containsAttribute("passwordForm")) {
            model.addAttribute("passwordForm", new PasswordForm());
        }
        return "profile";
    }

    @PostMapping("/profile")
    public String update(@Valid @ModelAttribute("profileForm") ProfileForm form, BindingResult binding,
                         RedirectAttributes flash) {
        if (binding.hasErrors()) {
            flash.addFlashAttribute(WebSupport.FLASH_ERROR, WebSupport.firstError(binding));
            return "redirect:/profile";
        }
        accounts.updateProfile(current.require(), form.getFullName(), form.getEmail(), form.getPhone());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Your profile was updated.");
        return "redirect:/profile";
    }

    @PostMapping("/profile/password")
    public String changePassword(@Valid @ModelAttribute("passwordForm") PasswordForm form, BindingResult binding,
                                 RedirectAttributes flash) {
        if (binding.hasErrors()) {
            flash.addFlashAttribute(WebSupport.FLASH_ERROR, "Fill in all three password fields.");
            return "redirect:/profile";
        }
        accounts.changePassword(current.require(), form.getCurrentPassword(), form.getNewPassword(),
                form.getConfirmPassword());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Your password was changed.");
        return "redirect:/profile";
    }
}
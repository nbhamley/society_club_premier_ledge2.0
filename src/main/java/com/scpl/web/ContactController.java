package com.scpl.web;

import com.scpl.dto.ContactRequest;
import com.scpl.dto.InviteTicket;
import com.scpl.dto.NewAdminRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.AdminApproval;
import com.scpl.service.ContactService;
import com.scpl.service.SocietyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.regex.Pattern;

/**
 * Admin team and contact cards (§11), plus admin invite and removal (§7). Both change who can vote, so both are
 * approvals: an invite creates the login only when it is approved, and a removal only when it is approved.
 */
@Controller
@RequestMapping("/society-admin")
@RequiredArgsConstructor
public class ContactController {

    static final String INVITE_TICKET = "inviteTicket";
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,40}$");

    private final AdminAccess access;
    private final ContactService contacts;
    private final SocietyService societies;

    @GetMapping("/contacts")
    public String page(Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("contactsPage", contacts.page(c.actor(), c.societyId()));
        return "contacts";
    }

    @PostMapping("/contacts")
    public String add(@Valid @ModelAttribute ContactRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        contacts.add(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Contact added.");
        return "redirect:/society-admin/contacts";
    }

    @PostMapping("/contacts/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        contacts.delete(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Contact deleted.");
        return "redirect:/society-admin/contacts";
    }

    /**
     * The temporary password is shown once, from a flash attribute, and only its hash is stored. The invited admin
     * can change it under /profile after signing in; nothing forces them to.
     */
    @PostMapping("/admins/invite")
    public String invite(@Valid @ModelAttribute NewAdminRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        if (!USERNAME.matcher(req.username().trim()).matches()) {
            throw new BusinessRuleException("The username must be 3 to 40 letters, digits, dots, dashes or underscores");
        }
        InviteTicket ticket = societies.requestAdminInvite(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(INVITE_TICKET, ticket);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Invitation sent for approval. The login is created when the other admins approve it. "
                + "Copy the temporary password now: it is shown only once.");
        return "redirect:/society-admin/contacts";
    }

    @PostMapping("/admins/{id}/remove")
    public String remove(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        AdminApproval a = societies.requestAdminRemoval(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, WebSupport.approvalRaised("Admin removal requested.", a));
        return "redirect:/society-admin/contacts";
    }
}
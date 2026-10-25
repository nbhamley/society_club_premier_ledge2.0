package com.scpl.web;

import com.scpl.dto.AdminViews;
import com.scpl.model.VendorCategory;
import com.scpl.service.VendorService;
import com.scpl.web.form.VendorForm;
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

/** Vendor directory (§11), Standard plan and above. The wa.me, tel: and mailto: links are built in VendorService. */
@Controller
@RequestMapping("/society-admin/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final AdminAccess access;
    private final VendorService vendors;

    @GetMapping
    public String page(@RequestParam(required = false) VendorCategory category, Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("vendors", vendors.list(c.actor(), c.societyId(), category));
        model.addAttribute("category", category);
        model.addAttribute("opts", AdminViews.FormOptions.all());
        return "vendors";
    }

    /** Create, or update when the form carries a vendor id. */
    @PostMapping
    public String save(@Valid @ModelAttribute VendorForm form, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        if (form.getId() == null) {
            vendors.create(c.actor(), c.societyId(), form.toRequest());
        } else {
            vendors.update(c.actor(), c.societyId(), form.getId(), form.toRequest());
        }
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Vendor saved.");
        return "redirect:/society-admin/vendors";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        vendors.delete(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Vendor deleted.");
        return "redirect:/society-admin/vendors";
    }
}
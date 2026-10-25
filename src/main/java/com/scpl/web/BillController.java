package com.scpl.web;

import com.scpl.dto.AdminViews;
import com.scpl.dto.BillRequest;
import com.scpl.model.BillStatus;
import com.scpl.service.AdminConsoleService;
import com.scpl.service.BillService;
import com.scpl.service.VendorService;
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

/** Vendor bills and the budget dashboard (§11). Bills are only ever visible to the owning admin team (§14). */
@Controller
@RequestMapping("/society-admin/bills")
@RequiredArgsConstructor
public class BillController {

    private final AdminAccess access;
    private final BillService bills;
    private final AdminConsoleService views;
    private final VendorService vendors;

    @GetMapping
    public String page(@RequestParam(required = false) Long season, Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("bills", views.billRows(c.actor(), c.societyId(), season));
        model.addAttribute("budget", bills.budget(c.actor(), c.societyId(), season));
        model.addAttribute("vendors", vendors.list(c.actor(), c.societyId(), null));
        model.addAttribute("seasons", views.seasonRows(c.actor(), c.societyId()));
        model.addAttribute("selectedSeasonId", season);
        model.addAttribute("opts", AdminViews.FormOptions.all());
        return "bills";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute BillRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        bills.create(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Bill added.");
        return "redirect:/society-admin/bills";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute BillRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        bills.update(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Bill saved.");
        return "redirect:/society-admin/bills";
    }

    @PostMapping("/{id}/status")
    public String status(@PathVariable Long id, @RequestParam BillStatus status, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        bills.setStatus(c.actor(), c.societyId(), id, status);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Bill marked " + status.getLabel().toLowerCase() + ".");
        return "redirect:/society-admin/bills";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        bills.delete(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Bill deleted.");
        return "redirect:/society-admin/bills";
    }
}
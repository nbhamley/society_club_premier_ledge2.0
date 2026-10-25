package com.scpl.web;

import com.scpl.dto.AdminViews;
import com.scpl.dto.BillingView;
import com.scpl.model.SubscriptionPlan;
import com.scpl.service.PaymentService.PaymentResult;
import com.scpl.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Subscription status, plan change, payment and invoices (§12). Everything under /society-admin/billing is exempt from
 * ReadOnlyGuard so a locked society can pay to recover. /billing/pay and /billing/auto-renew are additions to §7: without
 * a pay action there is no way to move from TRIAL to ACTIVE.
 */
@Controller
@RequestMapping("/society-admin/billing")
@RequiredArgsConstructor
public class BillingController {

    private final AdminAccess access;
    private final SubscriptionService subscriptions;

    @GetMapping
    public String page(Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("billing", subscriptions.billingView(c.actor(), c.societyId()));
        model.addAttribute("opts", AdminViews.FormOptions.all());
        return "billing";
    }

    @PostMapping("/plan")
    public String plan(@RequestParam SubscriptionPlan plan, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        subscriptions.changePlan(c.actor(), c.societyId(), plan);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Plan set to " + plan.getLabel() + ".");
        return "redirect:/society-admin/billing";
    }

    /** A declined payment comes back as a result, not an exception, so its audit row is kept. */
    @PostMapping("/pay")
    public String pay(@RequestParam(required = false) SubscriptionPlan plan, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        PaymentResult result = subscriptions.payNow(c.actor(), c.societyId(), plan);
        if (result.success()) {
            flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Payment received. Your subscription is active.");
        } else {
            flash.addFlashAttribute(WebSupport.FLASH_ERROR, "The payment was not completed: " + result.failureReason());
        }
        return "redirect:/society-admin/billing";
    }

    @PostMapping("/auto-renew")
    public String autoRenew(@RequestParam boolean enabled, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        subscriptions.setAutoRenew(c.actor(), c.societyId(), enabled);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Auto-renew turned " + (enabled ? "on" : "off") + ".");
        return "redirect:/society-admin/billing";
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        BillingView.InvoiceFile f = subscriptions.invoicePdf(c.actor(), c.societyId(), id);
        return FileResponses.bytes(f.bytes(), f.fileName(), MediaType.APPLICATION_PDF);
    }
}
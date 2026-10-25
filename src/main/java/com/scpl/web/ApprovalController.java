package com.scpl.web;

import com.scpl.model.AdminApproval;
import com.scpl.model.Vote;
import com.scpl.service.AdminApprovalService;
import com.scpl.service.AdminConsoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pending queue, voting, history and the society's audit log (§6, §15). Both POSTs first load the approval through
 * the society-scoped finder, so another society's approval id is a 404, not a 403 that confirms it exists.
 * The 3-of-N rules themselves (requester cannot vote, no double votes, conflict of interest) live in AdminApprovalService.
 */
@Controller
@RequestMapping("/society-admin/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final AdminAccess access;
    private final AdminApprovalService approvals;
    private final AdminConsoleService views;

    @GetMapping
    public String page(@RequestParam(defaultValue = "0") int page, Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("page", views.approvalsPage(c.actor(), c.societyId(), page));
        return "approvals";
    }

    @PostMapping("/{id}/vote")
    public String vote(@PathVariable Long id, @RequestParam Vote vote, @RequestParam(required = false) String comment,
                       RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        approvals.get(id, c.societyId());
        AdminApproval a = approvals.castVote(c.actor(), id, vote, comment == null || comment.isBlank() ? null
                : comment.length() > 500 ? comment.substring(0, 500) : comment.trim());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, a.isPending() ? "Your vote was recorded."
                : "Your vote was recorded. The approval is now " + a.getStatus().getLabel().toLowerCase() + ".");
        return "redirect:/society-admin/approvals";
    }

    @PostMapping("/{id}/withdraw")
    public String withdraw(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        approvals.get(id, c.societyId());
        approvals.withdraw(c.actor(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Approval withdrawn.");
        return "redirect:/society-admin/approvals";
    }
}
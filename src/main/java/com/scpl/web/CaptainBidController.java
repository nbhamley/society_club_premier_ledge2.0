package com.scpl.web;

import com.scpl.dto.AdminViews.LotRow;
import com.scpl.dto.DashboardViews.TeamHomeView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.User;
import com.scpl.security.CurrentUser;
import com.scpl.service.AdminConsoleService;
import com.scpl.service.AuctionService;
import com.scpl.service.DashboardService;
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
 * Online bidding for team captains (§6). Lives under /me because captains are SOCIETY_PLAYER users and cannot
 * enter /society-admin. AuctionService enforces everything that matters: the bidder must captain that team (or be
 * an admin), the lot must be live and online, and the bid must beat the standing one and fit the purse.
 */
@Controller
@RequestMapping("/me/auction")
@RequiredArgsConstructor
public class CaptainBidController {

    private final CurrentUser current;
    private final AuctionService auctions;
    private final DashboardService dashboard;
    private final AdminConsoleService views;

    /** Shows the live lot of the captain's season, when there is one. The template is my-auction.html. */
    @GetMapping
    public String page(Model model) {
        User u = current.require();
        TeamHomeView team = dashboard.teamHome(u);
        LotRow lot = team == null ? null : views.liveLot(u.getSocietyId(), team.seasonId());
        model.addAttribute("team", team);
        model.addAttribute("lot", lot);
        return "my-auction";
    }

    @PostMapping("/lots/{lotId}/bid")
    public String bid(@PathVariable Long lotId, @RequestParam Long teamId, @RequestParam double amount,
                      RedirectAttributes flash) {
        if (!(amount > 0) || Double.isInfinite(amount)) {
            throw new BusinessRuleException("Enter a bid greater than zero");
        }
        auctions.placeBid(current.require(), lotId, teamId, amount);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Your bid was placed.");
        return "redirect:/me/auction";
    }
}
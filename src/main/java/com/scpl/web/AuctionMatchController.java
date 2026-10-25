package com.scpl.web;

import com.scpl.dto.MatchRequest;
import com.scpl.dto.MatchResultRequest;
import com.scpl.model.AuctionMode;
import com.scpl.service.AuctionService;
import com.scpl.service.MatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Auction and match tabs. The optional "season" parameter only keeps the admin on the same season after the
 * redirect; the service checks every id against the admin's own society regardless.
 */
@Controller
@RequestMapping("/society-admin")
@RequiredArgsConstructor
public class AuctionMatchController {

    private final AdminAccess access;
    private final AuctionService auctions;
    private final MatchService matches;

    // ------------------------------------------------------------------ auction

    @PostMapping("/auction/lots")
    public String addLot(@RequestParam Long seasonId, @RequestParam Long playerId, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.addLot(c.actor(), c.societyId(), seasonId, playerId);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Lot added.");
        return auction(seasonId);
    }

    @PostMapping("/auction/lots/{id}/start")
    public String start(@PathVariable Long id, @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.start(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Lot is live.");
        return auction(season);
    }

    @PostMapping("/auction/lots/{id}/mode")
    public String mode(@PathVariable Long id, @RequestParam AuctionMode mode,
                       @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.setMode(c.actor(), c.societyId(), id, mode);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Lot mode set to " + mode.getLabel() + ".");
        return auction(season);
    }

    @PostMapping("/auction/lots/{id}/bid-override")
    public String override(@PathVariable Long id, @RequestParam Long teamId, @RequestParam double amount,
                           @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.overrideBid(c.actor(), c.societyId(), id, teamId, requireAmount(amount));
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Bid set.");
        return auction(season);
    }

    /** Online lots default to the standing bid; for an offline (hall) sale the admin supplies team and price. */
    @PostMapping("/auction/lots/{id}/sold")
    public String sold(@PathVariable Long id, @RequestParam(required = false) Long teamId,
                       @RequestParam(required = false) Double price, @RequestParam(required = false) Long season,
                       RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.markSold(c.actor(), c.societyId(), id, teamId, price);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Sale recorded.");
        return auction(season);
    }

    @PostMapping("/auction/lots/{id}/unsold")
    public String unsold(@PathVariable Long id, @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.markUnsold(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Lot marked unsold.");
        return auction(season);
    }

    @PostMapping("/auction/lots/{id}/reset")
    public String reset(@PathVariable Long id, @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        auctions.reset(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Lot reset.");
        return auction(season);
    }

    // ------------------------------------------------------------------ matches

    @PostMapping("/matches")
    public String schedule(@Valid @ModelAttribute MatchRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        matches.schedule(c.actor(), c.societyId(), req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Match scheduled.");
        return matchesTab(req.seasonId());
    }

    @PostMapping("/matches/{id}")
    public String reschedule(@PathVariable Long id, @Valid @ModelAttribute MatchRequest req, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        matches.reschedule(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Match updated.");
        return matchesTab(req.seasonId());
    }

    @PostMapping("/matches/{id}/result")
    public String result(@PathVariable Long id, @Valid @ModelAttribute MatchResultRequest req,
                         @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        matches.recordResult(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Result saved.");
        return matchesTab(season);
    }

    @PostMapping("/matches/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        matches.cancel(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Match cancelled.");
        return matchesTab(season);
    }

    @PostMapping("/matches/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam(required = false) Long season, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        matches.delete(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Match deleted.");
        return matchesTab(season);
    }

    // ------------------------------------------------------------------ helpers

    /** Rejects NaN, infinity and non-positive values before they reach the service. */
    private static double requireAmount(double amount) {
        if (!(amount > 0) || Double.isInfinite(amount)) {
            throw new com.scpl.exception.BusinessRuleException("Enter an amount greater than zero");
        }
        return amount;
    }

    private static String auction(Long season) {
        return "redirect:/society-admin" + (season == null ? "" : "?season=" + season) + "#auction";
    }

    private static String matchesTab(Long season) {
        return "redirect:/society-admin" + (season == null ? "" : "?season=" + season) + "#matches";
    }
}
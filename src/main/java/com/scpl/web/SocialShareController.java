package com.scpl.web;

import com.scpl.dto.ShareViews;
import com.scpl.model.SharePlatform;
import com.scpl.security.CurrentUser;
import com.scpl.service.SocialShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Share flow (§13).
 *
 * <ul>
 *   <li>GET /share/{type}/{id}/preview?platform=X : the "This share is public ..." confirmation text (JSON, stores nothing)</li>
 *   <li>POST /share/{type}/{id} : stores the SocialShare and returns the outbound URL or the Instagram/Facebook modal data (JSON)</li>
 *   <li>GET /share/{token} : public landing page that the shared deep link points to</li>
 * </ul>
 * Ownership (the player themselves, or an admin of the owning society) is enforced in SocialShareService.
 */
@Controller
@RequiredArgsConstructor
public class SocialShareController {

    private final CurrentUser current;
    private final SocialShareService shares;

    @GetMapping("/share/{type}/{id}/preview")
    @ResponseBody
    public ResponseEntity<ShareViews.Preview> preview(@PathVariable String type, @PathVariable Long id,
                                                      @RequestParam SharePlatform platform) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(shares.preview(current.require(), platform, type, id));
    }

    @PostMapping("/share/{type}/{id}")
    @ResponseBody
    public ResponseEntity<ShareViews.Result> create(@PathVariable String type, @PathVariable Long id,
                                                    @RequestParam SharePlatform platform,
                                                    @RequestParam(required = false) String caption) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(shares.create(current.require(), platform, type, id, caption));
    }

    /** Public. Tokens are random; the page shows only stats-level public information. */
    @GetMapping("/share/{token}")
    public String landing(@PathVariable String token, Model model) {
        model.addAttribute("landing", shares.landing(token));
        return "share/landing";
    }
}
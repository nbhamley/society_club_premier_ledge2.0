package com.scpl.web;

import com.scpl.dto.AwardRequest;
import com.scpl.dto.GalleryRequest;
import com.scpl.service.ContentService;
import com.scpl.web.form.SponsorForm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Sponsors, awards and gallery tabs. One endpoint creates (no id) or updates (id in the path). Logo, image and
 * website URLs are validated to http(s) or a site-relative path in ContentService, so a "javascript:" link cannot be stored.
 */
@Controller
@RequestMapping("/society-admin")
@RequiredArgsConstructor
public class ContentController {

    private final AdminAccess access;
    private final ContentService content;

    @PostMapping({"/sponsors", "/sponsors/{id}"})
    public String saveSponsor(@PathVariable(required = false) Long id, @Valid @ModelAttribute SponsorForm form,
                              RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.saveSponsor(c.actor(), c.societyId(), id, form.toRequest());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Sponsor saved.");
        return "redirect:/society-admin#sponsors";
    }

    @PostMapping("/sponsors/{id}/delete")
    public String deleteSponsor(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.deleteSponsor(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Sponsor deleted.");
        return "redirect:/society-admin#sponsors";
    }

    @PostMapping({"/awards", "/awards/{id}"})
    public String saveAward(@PathVariable(required = false) Long id, @Valid @ModelAttribute AwardRequest req,
                            RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.saveAward(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Award saved.");
        return "redirect:/society-admin#awards";
    }

    @PostMapping("/awards/{id}/delete")
    public String deleteAward(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.deleteAward(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Award deleted.");
        return "redirect:/society-admin#awards";
    }

    @PostMapping({"/gallery", "/gallery/{id}"})
    public String saveImage(@PathVariable(required = false) Long id, @Valid @ModelAttribute GalleryRequest req,
                            RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.saveImage(c.actor(), c.societyId(), id, req);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Image saved.");
        return "redirect:/society-admin#gallery";
    }

    @PostMapping("/gallery/{id}/delete")
    public String deleteImage(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        content.deleteImage(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Image deleted.");
        return "redirect:/society-admin#gallery";
    }
}
package com.scpl.web;

import com.scpl.dto.BackupView;
import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.service.BackupService;
import com.scpl.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Encrypted exports (§10). These routes are exempt from ReadOnlyGuard, so a PAST_DUE or CANCELLED society can still take
 * its final backup. The file can only be decrypted by this application (Platform Admin, Restore).
 */
@Controller
@RequestMapping("/society-admin/backup")
@RequiredArgsConstructor
public class BackupController {

    private final AdminAccess access;
    private final BackupService backups;
    private final SubscriptionService subscriptions;

    @GetMapping
    public String page(Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("backups", backups.list(c.actor(), c.societyId()));
        model.addAttribute("readOnly", subscriptions.isReadOnly(c.societyId()));
        return "backup";
    }

    @PostMapping
    public String generate(RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        BackupView v = backups.generate(c.actor(), c.societyId());
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Backup created: " + v.fileName()
                + ". It is kept for 30 days. Download it and store it somewhere safe.");
        return "redirect:/society-admin/backup";
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        FileDownload f = backups.download(c.actor(), c.societyId(), id);
        return FileResponses.attachment(f.path(), f.fileName(), MediaType.APPLICATION_OCTET_STREAM);
    }
}
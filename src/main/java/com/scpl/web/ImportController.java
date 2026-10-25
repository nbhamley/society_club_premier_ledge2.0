package com.scpl.web;

import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Bulk import UI (§9): upload, review.csv download, replace after offline edits, approve (raises the 3-of-N approval),
 * reject. the 10 MB limit is enforced by ImportService and admin.js, not by Spring’s multipart settings, again inside ImportService before any parsing,
 * and in the browser by admin.js (which reads data-max-bytes from the form).
 */
@Controller
@RequestMapping("/society-admin/import")
@RequiredArgsConstructor
public class ImportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final AdminAccess access;
    private final ImportService imports;

    @Value("${scpl.uploads.max-bytes:10485760}")
    private long maxBytes;

    @GetMapping
    public String page(@RequestParam(required = false) Long batch, Model model) {
        AdminAccess.Ctx c = access.ctx();
        model.addAttribute("batches", imports.list(c.actor(), c.societyId()));
        model.addAttribute("maxBytes", maxBytes);
        if (batch != null) {
            model.addAttribute("previewBatchId", batch);
            try {
                model.addAttribute("preview", imports.preview(c.actor(), c.societyId(), batch));
            } catch (NotFoundException e) {
                model.addAttribute("previewError", e.getMessage());
            }
        }
        return "import";
    }

    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        imports.submit(c.actor(), c.societyId(), file);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS,
                "Upload received. It is analysed within a minute and the admin team is notified when the review file is ready.");
        return "redirect:/society-admin/import";
    }

    @GetMapping("/{id}/review")
    public ResponseEntity<Resource> review(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        FileDownload f = imports.download(c.actor(), c.societyId(), id, false);
        return FileResponses.attachment(f.path(), f.fileName(), CSV);
    }

    @GetMapping("/{id}/failed")
    public ResponseEntity<Resource> failed(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        FileDownload f = imports.download(c.actor(), c.societyId(), id, true);
        return FileResponses.attachment(f.path(), f.fileName(), CSV);
    }

    @PostMapping("/{id}/replace")
    public String replace(@PathVariable Long id, @RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        imports.replaceReview(c.actor(), c.societyId(), id, file);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Review file replaced. Conflicts were checked again.");
        return "redirect:/society-admin/import?batch=" + id;
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        AdminApproval a = imports.requestApproval(c.actor(), c.societyId(), id);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, WebSupport.approvalRaised("Import sent for approval.", a));
        return "redirect:/society-admin/import";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes flash) {
        AdminAccess.Ctx c = access.ctx();
        imports.reject(c.actor(), c.societyId(), id, reason);
        flash.addFlashAttribute(WebSupport.FLASH_SUCCESS, "Import rejected. Its files are deleted after the retention period.");
        return "redirect:/society-admin/import";
    }
}
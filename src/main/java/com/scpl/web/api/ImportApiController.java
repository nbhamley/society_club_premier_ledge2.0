package com.scpl.web.api;

import com.scpl.dto.ImportRows.BatchView;
import com.scpl.dto.ImportRows.FileDownload;
import com.scpl.dto.ImportRows.ReviewPreview;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.AdminApproval;
import com.scpl.model.UploadBatch;
import com.scpl.service.ImportService;
import com.scpl.web.AdminAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * JSON twin of the Import tab (§9). It shares the same ImportService calls as the Thymeleaf controller, so there is no
 * second copy of the rules. Society admins only (SecurityConfig). CSRF protection stays ON: send the token as the
 * X-CSRF-TOKEN header on POST and PATCH. Errors are JSON ({"error": "..."}) from GlobalExceptionHandler.
 *
 * <pre>
 * GET    /api/v1/imports                  list batches
 * POST   /api/v1/imports                  upload (multipart "file", max 10 MB)       -> 201 + batch
 * GET    /api/v1/imports/{id}             one batch
 * GET    /api/v1/imports/{id}/review      review rows as JSON (first 200)
 * GET    /api/v1/imports/{id}/review.csv  review file download
 * GET    /api/v1/imports/{id}/failed.csv  failed-rows file download
 * POST   /api/v1/imports/{id}/review      replace the review file after offline edits (multipart "file")
 * PATCH  /api/v1/imports/{id}             {"action":"APPROVE"} raises the 3-of-N approval, {"action":"REJECT","reason":"..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
public class ImportApiController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    public record ImportAction(@NotBlank String action, String reason) {
    }

    public record ApprovalRaised(Long approvalId, int approvalsRequired, int eligibleVoters, String status) {
    }

    public record PatchResult(BatchView batch, ApprovalRaised approval) {
    }

    private final AdminAccess access;
    private final ImportService imports;

    @GetMapping
    public List<BatchView> list() {
        AdminAccess.Ctx c = access.ctx();
        return imports.list(c.actor(), c.societyId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchView> upload(@RequestParam("file") MultipartFile file) {
        AdminAccess.Ctx c = access.ctx();
        UploadBatch b = imports.submit(c.actor(), c.societyId(), file);
        return ResponseEntity.created(URI.create("/api/v1/imports/" + b.getId()))
                .body(imports.get(c.actor(), c.societyId(), b.getId()));
    }

    @GetMapping("/{id}")
    public BatchView one(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        return imports.get(c.actor(), c.societyId(), id);
    }

    @GetMapping("/{id}/review")
    public ReviewPreview review(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        return imports.preview(c.actor(), c.societyId(), id);
    }

    @GetMapping("/{id}/review.csv")
    public ResponseEntity<Resource> reviewFile(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        FileDownload f = imports.download(c.actor(), c.societyId(), id, false);
        return com.scpl.web.FileResponsesBridge.attachment(f.path(), f.fileName(), CSV);
    }

    @GetMapping("/{id}/failed.csv")
    public ResponseEntity<Resource> failedFile(@PathVariable Long id) {
        AdminAccess.Ctx c = access.ctx();
        FileDownload f = imports.download(c.actor(), c.societyId(), id, true);
        return com.scpl.web.FileResponsesBridge.attachment(f.path(), f.fileName(), CSV);
    }

    @PostMapping(value = "/{id}/review", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchView replaceReview(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        AdminAccess.Ctx c = access.ctx();
        imports.replaceReview(c.actor(), c.societyId(), id, file);
        return imports.get(c.actor(), c.societyId(), id);
    }

    @PatchMapping("/{id}")
    public PatchResult act(@PathVariable Long id, @Valid @RequestBody ImportAction body) {
        AdminAccess.Ctx c = access.ctx();
        switch (body.action().trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> {
                AdminApproval a = imports.requestApproval(c.actor(), c.societyId(), id);
                return new PatchResult(imports.get(c.actor(), c.societyId(), id), new ApprovalRaised(a.getId(),
                        a.getApprovalsRequired(), a.getEligibleVoters(), a.getStatus().name()));
            }
            case "REJECT" -> {
                imports.reject(c.actor(), c.societyId(), id, body.reason());
                return new PatchResult(imports.get(c.actor(), c.societyId(), id), null);
            }
            default -> throw new BusinessRuleException("action must be APPROVE or REJECT");
        }
    }
}
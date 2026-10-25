package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "upload_batches")
@Getter
@Setter
@NoArgsConstructor
public class UploadBatch extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "mime_type")
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status = BatchStatus.UPLOADED;

    /** JSON summary of what the segregation step recognised. */
    @Column(name = "segregation_report")
    private String segregationReport;

    @Column(name = "review_file_url")
    private String reviewFileUrl;

    /** Rows that failed on insert, offered for download so the admin can fix and re-upload. */
    @Column(name = "failed_file_url")
    private String failedFileUrl;

    /** The BULK_IMPORT approval governing this batch, once the admin raises it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_id")
    private AdminApproval approval;

    @Column(name = "review_deadline")
    private LocalDateTime reviewDeadline;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "error_message")
    private String errorMessage;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isAwaitingReview() {
        return status == BatchStatus.READY_FOR_REVIEW;
    }
}
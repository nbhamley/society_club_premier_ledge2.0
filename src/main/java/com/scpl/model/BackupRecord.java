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
@Table(name = "backup_records")
@Getter
@Setter
@NoArgsConstructor
public class BackupRecord extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    /** scpl_backup_{societyId}_{yyyyMMddHHmmss}.scplenc */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(nullable = false)
    private Boolean encrypted = Boolean.TRUE;

    private String algorithm;

    /** Checksum of the encrypted file; RestoreService verifies it before decrypting. */
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupStatus status = BackupStatus.GENERATING;

    /** createdAt + 30 days; BackupPurgeJob deletes the file and marks the record after this. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    public boolean isDownloadable() {
        return status == BackupStatus.READY || status == BackupStatus.DOWNLOADED;
    }
}
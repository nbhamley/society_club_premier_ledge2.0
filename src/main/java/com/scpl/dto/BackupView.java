package com.scpl.dto;

import com.scpl.model.BackupStatus;

import java.time.LocalDateTime;

public record BackupView(Long id, String fileName, String sizeLabel, BackupStatus status, String statusLabel,
                         boolean encrypted, String algorithm, String sha256, LocalDateTime createdAt,
                         LocalDateTime expiresAt, boolean downloadable) {
}
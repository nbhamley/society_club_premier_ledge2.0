package com.scpl.dto;

import com.scpl.model.BatchStatus;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Value types shared by the readers, the segregation providers and ImportService. */
public final class ImportRows {

    private ImportRows() {
    }

    /** One sheet, CSV or JSON array, with rows keyed by header text. */
    public record Table(String source, List<String> headers, List<Map<String, String>> rows) {
    }

    /** A recognised row, keyed by canonical field names (see ImportSchema). */
    public record SegregatedRow(ImportKind kind, String sourceFile, int sourceRow, Map<String, String> fields,
                                int confidence) {
    }

    public record TableSummary(String source, ImportKind kind, int confidence, int rows, List<String> unmappedHeaders) {
    }

    /** aiAllowed is false when the society's plan excludes AI import; providers must then not call out. */
    public record SegregationInput(Long societyId, String fileName, Path file, List<Table> tables, boolean aiAllowed) {
    }

    public record SegregationResult(String provider, List<SegregatedRow> rows, List<TableSummary> tables,
                                    List<String> warnings) {
    }

    /** One line of review.csv. conflict is null when the row looks importable. */
    public record ReviewRow(ImportKind kind, String sourceFile, int sourceRow, boolean include,
                            Map<String, String> fields, int confidence, String conflict) {
        public ReviewRow withConflict(String newConflict) {
            return new ReviewRow(kind, sourceFile, sourceRow, include, fields, confidence, newConflict);
        }
    }

    /** Stored as JSON in UploadBatch.segregationReport. */
    public record SegregationReport(String provider, int totalRows, Map<String, Integer> countsByKind,
                                    Map<String, Integer> conflictCounts, int rowsWithConflicts,
                                    List<TableSummary> tables, List<String> warnings) {
    }

    public record ReviewPreview(int total, int included, int withConflicts, List<ReviewRow> rows) {
    }

    public record FileDownload(String fileName, Path path) {
    }

    /** Row model for the Import tab. */
    public record BatchView(Long id, String fileName, long sizeBytes, String sizeLabel, BatchStatus status,
                            String statusLabel, String uploadedBy, LocalDateTime createdAt,
                            LocalDateTime reviewDeadline, String errorMessage, boolean hasReview, boolean hasFailed,
                            Long approvalId, String approvalStatusLabel, SegregationReport report) {
    }
}
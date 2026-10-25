package com.scpl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record BillRequest(@NotNull Long vendorId, Long seasonId, @NotBlank String title, @NotNull Double amount,
                          LocalDate billDate, String description, String attachmentUrl) {
}
package com.scpl.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record SeasonRequest(@NotBlank String name, Integer year, LocalDate startDate, LocalDate endDate,
                            Integer oversPerMatch, Double teamPurse, Double plannedBudget, String description) {
}
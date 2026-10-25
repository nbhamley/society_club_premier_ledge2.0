package com.scpl.dto;

import com.scpl.model.AwardCategory;
import jakarta.validation.constraints.NotBlank;

public record AwardRequest(@NotBlank String title, AwardCategory category, String description, String imageUrl,
                           Long seasonId, Long playerId, Long teamId) {
}
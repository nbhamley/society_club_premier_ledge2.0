package com.scpl.dto;

import com.scpl.model.SponsorTier;
import jakarta.validation.constraints.NotBlank;

public record SponsorRequest(@NotBlank String name, SponsorTier tier, String logoUrl, String website,
                             Double amount, Long seasonId, Boolean active) {
}
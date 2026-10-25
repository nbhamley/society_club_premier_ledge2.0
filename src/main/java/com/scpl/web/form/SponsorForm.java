package com.scpl.web.form;

import com.scpl.dto.SponsorRequest;
import com.scpl.model.SponsorTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SponsorForm {

    @NotBlank
    @Size(max = 160)
    private String name;
    private SponsorTier tier;
    @Size(max = 300)
    private String logoUrl;
    @Size(max = 300)
    private String website;
    @PositiveOrZero
    private Double amount;
    private Long seasonId;
    private Boolean active = Boolean.TRUE;

    public SponsorRequest toRequest() {
        return new SponsorRequest(name, tier, logoUrl, website, amount, seasonId, Boolean.TRUE.equals(active));
    }
}
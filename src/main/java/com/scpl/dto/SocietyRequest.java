package com.scpl.dto;

import jakarta.validation.constraints.NotBlank;

public record SocietyRequest(@NotBlank String name, String slug, String description, String city,
                             String address, String contactEmail, String contactPhone) {
}
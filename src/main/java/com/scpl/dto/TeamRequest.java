package com.scpl.dto;

import jakarta.validation.constraints.NotBlank;

public record TeamRequest(@NotBlank String name, String shortName, String color, String logoUrl) {
}
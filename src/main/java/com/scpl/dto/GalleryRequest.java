package com.scpl.dto;

import jakarta.validation.constraints.NotBlank;

public record GalleryRequest(String title, @NotBlank String imageUrl, String caption, Long seasonId) {
}
package com.scpl.dto;

import com.scpl.model.EventScope;
import com.scpl.model.EventType;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public record PlatformEventRequest(@NotBlank String title, String description, LocalDateTime eventDate,
                                   EventScope scope, Long societyId, EventType type, String thumbnailUrl,
                                   String externalLink, Boolean published) {
}
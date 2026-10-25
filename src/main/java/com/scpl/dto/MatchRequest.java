package com.scpl.dto;

import com.scpl.model.MatchStage;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record MatchRequest(@NotNull Long seasonId, @NotNull Long teamAId, @NotNull Long teamBId,
                           LocalDateTime matchDate, String venue, MatchStage stage) {
}
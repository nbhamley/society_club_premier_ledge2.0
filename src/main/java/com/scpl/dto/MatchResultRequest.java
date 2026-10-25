package com.scpl.dto;

import jakarta.validation.constraints.NotNull;

/** Overs use cricket notation: 9.3 means 9 overs and 3 balls. */
public record MatchResultRequest(@NotNull Integer teamARuns, @NotNull Integer teamAWickets, @NotNull Double teamAOvers,
                                 @NotNull Integer teamBRuns, @NotNull Integer teamBWickets, @NotNull Double teamBOvers,
                                 String resultSummary) {
}
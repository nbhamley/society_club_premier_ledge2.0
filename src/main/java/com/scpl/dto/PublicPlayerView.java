package com.scpl.dto;

import com.scpl.model.PlayerRole;

/** Roster row for public and cross-society pages. Has no PII fields at all, so a template cannot leak any. */
public record PublicPlayerView(Long id, String name, PlayerRole role, String teamName, String photoUrl,
                               int matchesPlayed, int runs, int wickets) {
}
package com.scpl.dto;

import com.scpl.model.BattingStyle;
import com.scpl.model.BowlingStyle;
import com.scpl.model.PlayerRole;

import java.time.LocalDate;

/**
 * Single-player page model. The PII fields are null unless piiVisible is true, and the
 * stripping happens in PlayerService, not in the template (§14).
 */
public record PlayerProfileView(Long id, String name, PlayerRole role, BattingStyle battingStyle,
                                BowlingStyle bowlingStyle, Integer jerseyNumber, String photoUrl,
                                Long teamId, String teamName, int matchesPlayed, int runs, int wickets,
                                boolean piiVisible, String email, String phone, LocalDate dateOfBirth,
                                String address, String flatNumber) {
}
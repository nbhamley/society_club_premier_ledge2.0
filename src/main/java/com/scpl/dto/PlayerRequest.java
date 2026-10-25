package com.scpl.dto;

import com.scpl.model.BattingStyle;
import com.scpl.model.BowlingStyle;
import com.scpl.model.PlayerRole;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record PlayerRequest(@NotBlank String name, String email, String phone, LocalDate dateOfBirth,
                            String address, String flatNumber, PlayerRole role, BattingStyle battingStyle,
                            BowlingStyle bowlingStyle, Integer jerseyNumber, Double basePrice,
                            String photoUrl, Long teamId) {
}
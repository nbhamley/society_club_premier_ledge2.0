package com.scpl.dto;

import com.scpl.model.BattingStyle;
import com.scpl.model.BowlingStyle;
import com.scpl.model.PlayerRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record RegistrationRequest(@NotBlank String fullName, @NotBlank @Email String email, String phone,
                                  LocalDate dateOfBirth, String flatNumber, PlayerRole role,
                                  BattingStyle battingStyle, BowlingStyle bowlingStyle, String notes) {
}
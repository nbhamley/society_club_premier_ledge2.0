package com.scpl.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** password is required when creating a society and ignored for invites (a temporary one is generated). */
public record NewAdminRequest(@NotBlank String username, @NotBlank @Email String email, String fullName,
                              String phone, String password) {
}
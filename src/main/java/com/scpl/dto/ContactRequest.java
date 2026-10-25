package com.scpl.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ContactRequest(@NotBlank @Size(max = 120) String name,
                             @Size(max = 80) String roleTitle,
                             @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -") String phone,
                             @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -") String whatsappNumber,
                             @Email @Size(max = 200) String email) {
}
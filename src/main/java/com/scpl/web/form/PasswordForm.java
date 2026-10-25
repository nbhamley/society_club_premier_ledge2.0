package com.scpl.web.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Never trimmed or logged. The rules (length, reuse, username) live in AccountService. */
@Data
public class PasswordForm {

    @NotBlank
    private String currentPassword;
    @NotBlank
    private String newPassword;
    @NotBlank
    private String confirmPassword;
}
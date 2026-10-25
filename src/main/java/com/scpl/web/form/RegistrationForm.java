package com.scpl.web.form;

import com.scpl.dto.RegistrationRequest;
import com.scpl.model.BattingStyle;
import com.scpl.model.BowlingStyle;
import com.scpl.model.PlayerRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegistrationForm {

    @NotNull(message = "choose a season")
    private Long seasonId;
    @NotBlank
    @Size(max = 120)
    private String fullName;
    @NotBlank
    @Email
    @Size(max = 200)
    private String email;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String phone;
    @Past
    private LocalDate dateOfBirth;
    @Size(max = 20)
    private String flatNumber;
    private PlayerRole role;
    private BattingStyle battingStyle;
    private BowlingStyle bowlingStyle;
    @Size(max = 500)
    private String notes;
    /** Honeypot, as on the subscribe form. */
    private String companyUrl;

    public RegistrationRequest toRequest() {
        return new RegistrationRequest(fullName, email, phone, dateOfBirth, flatNumber, role, battingStyle,
                bowlingStyle, notes);
    }
}
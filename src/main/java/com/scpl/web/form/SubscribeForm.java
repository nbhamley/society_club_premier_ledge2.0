package com.scpl.web.form;

import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.model.SubscriptionPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** /subscribe sign-up. A society needs at least 3 founding admins (§6), so the form starts with three blocks. */
@Data
public class SubscribeForm {

    @NotBlank
    @Size(max = 120)
    private String name;
    @Size(max = 80)
    private String city;
    @Size(max = 1000)
    private String description;
    @Email
    @Size(max = 200)
    private String contactEmail;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String contactPhone;
    private SubscriptionPlan plan;

    @Valid
    @Size(min = 3, max = 10, message = "a society needs between 3 and 10 admins")
    private List<AdminForm> admins = new ArrayList<>(List.of(new AdminForm(), new AdminForm(), new AdminForm()));

    /** Honeypot: hidden from people by CSS, filled in by naive bots. */
    private String companyUrl;

    @Data
    public static class AdminForm {
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,40}$", message = "must be 3 to 40 letters, digits, dots, dashes or underscores")
        private String username;
        @NotBlank
        @Email
        @Size(max = 200)
        private String email;
        @Size(max = 120)
        private String fullName;
        @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
        private String phone;
        @NotBlank
        @Size(min = 10, max = 72, message = "must be 10 to 72 characters")
        private String password;
    }

    public SocietyRequest toSocietyRequest() {
        return new SocietyRequest(name, null, description, city, null, contactEmail, contactPhone);
    }

    public List<NewAdminRequest> toAdminRequests() {
        return admins.stream().map(a -> new NewAdminRequest(a.getUsername().trim(), a.getEmail().trim(),
                a.getFullName(), a.getPhone(), a.getPassword())).toList();
    }
}
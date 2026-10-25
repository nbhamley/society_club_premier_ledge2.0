package com.scpl.web.form;

import com.scpl.dto.SocietyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Creation wizard (§6): three founding admins are required, so a society can never exist below the activation minimum. */
@Data
public class PlatformSocietyForm {

    @NotBlank
    @Size(max = 120)
    private String name;
    @Pattern(regexp = "^[a-z0-9-]{0,60}$", message = "may only contain lowercase letters, digits and dashes")
    private String slug;
    @Size(max = 80)
    private String city;
    @Size(max = 300)
    private String address;
    @Size(max = 1000)
    private String description;
    @Email
    @Size(max = 200)
    private String contactEmail;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String contactPhone;
    private boolean activateNow;

    @Valid
    @Size(min = 3, max = 10, message = "a society needs between 3 and 10 admins")
    private List<SubscribeForm.AdminForm> admins = new ArrayList<>(List.of(
            new SubscribeForm.AdminForm(), new SubscribeForm.AdminForm(), new SubscribeForm.AdminForm()));

    public SocietyRequest toSocietyRequest() {
        return new SocietyRequest(name, slug, description, city, address, contactEmail, contactPhone);
    }
}
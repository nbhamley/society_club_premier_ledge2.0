package com.scpl.web.form;

import com.scpl.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileForm {

    @Size(max = 120)
    private String fullName;
    @NotBlank
    @Email
    @Size(max = 200)
    private String email;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String phone;

    public static ProfileForm from(User u) {
        ProfileForm f = new ProfileForm();
        f.setFullName(u.getFullName());
        f.setEmail(u.getEmail());
        f.setPhone(u.getPhone());
        return f;
    }
}
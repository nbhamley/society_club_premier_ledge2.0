package com.scpl.web.form;

import com.scpl.dto.VendorRequest;
import com.scpl.model.VendorCategory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One form for create and update: a hidden id means update. A bean (not a record) so the checkbox's _active marker works. */
@Data
public class VendorForm {

    private Long id;
    @NotBlank
    @Size(max = 160)
    private String name;
    private VendorCategory category;
    @Size(max = 120)
    private String contactPerson;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String phone;
    @Pattern(regexp = "^[0-9+()\\-\\s]{0,30}$", message = "may only contain digits, spaces, + ( ) and -")
    private String whatsappNumber;
    @Email
    @Size(max = 200)
    private String email;
    @Size(max = 300)
    private String address;
    @Size(max = 80)
    private String city;
    @Size(max = 300)
    private String website;
    @Min(1)
    @Max(5)
    private Integer rating;
    @Size(max = 1000)
    private String notes;
    private Boolean active = Boolean.TRUE;

    public VendorRequest toRequest() {
        return new VendorRequest(name, category, contactPerson, phone, whatsappNumber, email, address, city, website,
                rating, notes, Boolean.TRUE.equals(active));
    }
}
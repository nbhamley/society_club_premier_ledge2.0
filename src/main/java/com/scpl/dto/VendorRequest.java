package com.scpl.dto;

import com.scpl.model.VendorCategory;
import jakarta.validation.constraints.NotBlank;

public record VendorRequest(@NotBlank String name, VendorCategory category, String contactPerson, String phone,
                            String whatsappNumber, String email, String address, String city, String website,
                            Integer rating, String notes, Boolean active) {
}
package com.scpl.dto;

import com.scpl.model.VendorCategory;

/** Row for the vendor directory, with the past-bill sum and ready-made contact links (§11). */
public record VendorView(Long id, String name, VendorCategory category, String categoryLabel,
                         String contactPerson, String phone, String whatsappNumber, String email,
                         String address, String city, String website, Integer rating, String notes,
                         boolean active, double billTotal, String whatsappLink, String callLink,
                         String mailLink) {
}
package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-society vendor directory entry; never shared across societies. */
@Entity
@Table(name = "vendor_contacts")
@Getter
@Setter
@NoArgsConstructor
public class VendorContact extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VendorCategory category = VendorCategory.OTHER;

    @Column(name = "contact_person")
    private String contactPerson;

    private String phone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    private String email;
    private String address;
    private String city;
    private String website;

    /** 1 to 5; enforced by a CHECK constraint and by validation in VendorService. */
    private Integer rating;

    private String notes;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    /** Digits only, as wa.me requires. Falls back to the phone number when no WhatsApp number is set. */
    public String getWhatsappDigits() {
        String source = whatsappNumber != null && !whatsappNumber.isBlank() ? whatsappNumber : phone;
        return source == null ? null : source.replaceAll("\\D", "");
    }
}
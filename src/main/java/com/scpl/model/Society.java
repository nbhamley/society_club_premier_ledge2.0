package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The tenant. Every other society-scoped row points back here. */
@Entity
@Table(name = "societies")
@Getter
@Setter
@NoArgsConstructor
public class Society extends TimestampedEntity implements SocietyScoped {

    @Column(nullable = false)
    private String name;

    /** URL slug for /society/{slug}; derived from the name but admin-editable. */
    @Column(nullable = false)
    private String slug;

    private String description;
    private String city;
    private String address;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SocietyStatus status = SocietyStatus.DRAFT;

    /**
     * Denormalised count of SOCIETY_ADMIN users; AdminApproval snapshots it at request time.
     * Kept in sync by SocietyService whenever an admin is added or removed.
     */
    @Column(name = "admin_count", nullable = false)
    private Integer adminCount = 0;

    @Override
    public Long getSocietyId() {
        return getId();
    }

    public boolean isActive() {
        return status == SocietyStatus.ACTIVE;
    }

    public boolean hasMinimumAdmins(int minimum) {
        return adminCount != null && adminCount >= minimum;
    }
}
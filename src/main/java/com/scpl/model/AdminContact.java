package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Contact card for the admin team (§11). user is optional because a committee member
 * can be listed without having a login.
 */
@Entity
@Table(name = "admin_contacts")
@Getter
@Setter
@NoArgsConstructor
public class AdminContact extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "society_id", nullable = false)
    private Society society;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(name = "role_title")
    private String roleTitle;

    private String phone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    private String email;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }

    /** Digits only, as wa.me requires (no +, spaces or dashes). */
    public String getWhatsappDigits() {
        return whatsappNumber == null ? null : whatsappNumber.replaceAll("\\D", "");
    }
}
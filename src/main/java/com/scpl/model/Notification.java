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

/** In-app notification shown in the admin bell menu; stands in for email delivery. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends TimestampedEntity implements SocietyScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    private Society society;

    @Column(nullable = false)
    private String title;

    private String message;

    /** Relative app link, for example /society-admin/approvals. */
    private String link;

    /** Attribute is "read" but the column is is_read because READ is awkward in SQL. */
    @Column(name = "is_read", nullable = false)
    private Boolean read = Boolean.FALSE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }
}
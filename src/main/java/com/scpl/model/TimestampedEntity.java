package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Adds the created_at column that most tables carry. */
@MappedSuperclass
@Getter
@Setter
public abstract class TimestampedEntity extends BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void stampCreated() {
        // Respect a pre-set value so the seeder and restore can back-date rows.
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
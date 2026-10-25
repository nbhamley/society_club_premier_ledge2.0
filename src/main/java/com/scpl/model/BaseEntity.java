package com.scpl.model;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;

/**
 * Common identity for every entity. equals/hashCode are based on the id only (spec §19).
 * hashCode is constant per class on purpose: an id-based hash would change when the id is
 * assigned on persist and break HashSet membership for new entities.
 * Hibernate.getClass() unwraps lazy proxies so a proxy equals its real entity.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Long otherId = ((BaseEntity) o).getId();
        return id != null && id.equals(otherId);
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
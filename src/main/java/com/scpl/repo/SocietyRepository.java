package com.scpl.repo;

import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocietyRepository extends JpaRepository<Society, Long> {

    Optional<Society> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Used when an admin edits the slug: it may match itself but nobody else. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    List<Society> findAllByOrderByNameAsc();

    List<Society> findByStatusOrderByNameAsc(SocietyStatus status);

    long countByStatus(SocietyStatus status);
}
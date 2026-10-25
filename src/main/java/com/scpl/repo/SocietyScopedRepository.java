package com.scpl.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * Base for every repository whose entity has a "society" association. Services should load
 * society-owned rows through findByIdAndSociety_Id so a wrong-tenant id comes back empty
 * instead of leaking another society's data. The inherited findById/findAll are unscoped
 * and are reserved for platform-level code and background jobs.
 */
@NoRepositoryBean
public interface SocietyScopedRepository<T, ID> extends JpaRepository<T, ID> {

    Optional<T> findByIdAndSociety_Id(ID id, Long societyId);

    List<T> findBySociety_Id(Long societyId);

    long countBySociety_Id(Long societyId);
}
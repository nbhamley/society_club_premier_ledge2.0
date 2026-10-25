package com.scpl.repo;

import com.scpl.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * A society admin's History tab. societyId is a plain column on AuditLog (no association),
     * so findBySocietyId is the correct derived form here, unlike the Society_Id traversals elsewhere.
     */
    @EntityGraph(attributePaths = "user")
    Page<AuditLog> findBySocietyIdOrderByTimestampDesc(Long societyId, Pageable pageable);

    /** Platform admin: every society's log. */
    @EntityGraph(attributePaths = "user")
    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    boolean existsBySocietyIdAndAction(Long societyId, String action);

    long countBySocietyIdAndAction(Long societyId, String action);
}
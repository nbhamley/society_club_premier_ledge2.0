package com.scpl.repo;

import com.scpl.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Bell menu and /notifications page. Capped so a busy admin's page stays small. */
    List<Notification> findTop50ByUser_IdOrderByCreatedAtDesc(Long userId);

    long countByUser_IdAndReadFalse(Long userId);

    /** Scoped to the owner so one user can never mark another user's notification read. */
    Optional<Notification> findByIdAndUser_Id(Long id, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.read = true where n.user.id = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId);

    /** Housekeeping: drop read notifications older than the cutoff. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Notification n where n.read = true and n.createdAt < :cutoff")
    int purgeReadBefore(@Param("cutoff") LocalDateTime cutoff);
}
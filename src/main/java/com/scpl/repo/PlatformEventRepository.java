package com.scpl.repo;

import com.scpl.model.EventScope;
import com.scpl.model.PlatformEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PlatformEventRepository extends JpaRepository<PlatformEvent, Long> {

    /** Platform home: upcoming published platform-wide events. */
    List<PlatformEvent> findTop6ByScopeAndPublishedTrueAndEventDateAfterOrderByEventDateAsc(
            EventScope scope, LocalDateTime after);

    /** Platform home thumbnail strip linking out to YouTube / social posts. */
    List<PlatformEvent> findTop6ByPublishedTrueAndThumbnailUrlIsNotNullOrderByCreatedAtDesc();

    /** A society's public page. */
    List<PlatformEvent> findBySociety_IdAndPublishedTrueAndEventDateAfterOrderByEventDateAsc(
            Long societyId, LocalDateTime after);

    /** Platform admin CRUD list. */
    @EntityGraph(attributePaths = "society")
    List<PlatformEvent> findAllByOrderByEventDateDesc();
}
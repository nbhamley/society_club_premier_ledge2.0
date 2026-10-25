package com.scpl.repo;

import com.scpl.model.SharePlatform;
import com.scpl.model.SocialShare;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocialShareRepository extends JpaRepository<SocialShare, Long> {

    /** Public /share/{token} landing page. Tokens are random and unguessable. */
    @EntityGraph(attributePaths = {"player", "team", "match", "award"})
    Optional<SocialShare> findByToken(String token);

    List<SocialShare> findByUser_IdOrderByCreatedAtDesc(Long userId);

    long countByPlatform(SharePlatform platform);

    long countByPlayer_Id(Long playerId);
}
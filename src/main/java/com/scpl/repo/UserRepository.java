package com.scpl.repo;

import com.scpl.model.PlatformRole;
import com.scpl.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends SocietyScopedRepository<User, Long> {

    /** Login lookup. The graph loads society and player so CurrentUser never hits a lazy proxy. */
    @EntityGraph(attributePaths = {"society", "player"})
    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCaseAndIdNot(String username, Long id);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    List<User> findBySociety_IdAndRoleOrderByFullNameAsc(Long societyId, PlatformRole role);

    /** Recipients for admin notifications; disabled accounts are skipped. */
    List<User> findBySociety_IdAndRoleAndEnabledTrue(Long societyId, PlatformRole role);

    long countBySociety_IdAndRole(Long societyId, PlatformRole role);

    List<User> findByRoleAndEnabledTrue(PlatformRole role);

    Optional<User> findByPlayer_Id(Long playerId);

    /** Targeted update so a login never rewrites the whole row (and never races other edits). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.lastLoginAt = :at where u.id = :id")
    int touchLastLogin(@Param("id") Long id, @Param("at") LocalDateTime at);
}
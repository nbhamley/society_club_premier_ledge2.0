package com.scpl.security;

import com.scpl.exception.TenantAccessException;
import com.scpl.model.PlatformRole;
import com.scpl.model.Player;
import com.scpl.model.Society;
import com.scpl.model.SocietyScoped;
import com.scpl.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantGuardTest {

    @Mock
    CurrentUser current;

    private TenantGuard guard;

    @BeforeEach
    void setUp() {
        guard = new TenantGuard(current);
    }

    private static User user(PlatformRole role, Long societyId, Long playerId) {
        User u = new User();
        u.setId(1L);
        u.setUsername("u");
        u.setRole(role);
        u.setEnabled(true);
        if (societyId != null) {
            Society s = new Society();
            s.setId(societyId);
            u.setSociety(s);
        }
        if (playerId != null) {
            Player p = new Player();
            p.setId(playerId);
            u.setPlayer(p);
        }
        return u;
    }

    @Test
    void sameSocietyPasses_otherSocietyIsForbidden() {
        when(current.require()).thenReturn(user(PlatformRole.SOCIETY_ADMIN, 1L, null));
        assertThatCode(() -> guard.assertSameSociety(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.assertSameSociety(2L)).isInstanceOf(TenantAccessException.class);
        assertThatThrownBy(() -> guard.assertSameSociety((Long) null)).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void entitiesAreCheckedThroughTheirSocietyId() {
        when(current.require()).thenReturn(user(PlatformRole.SOCIETY_PLAYER, 1L, null));
        SocietyScoped mine = () -> 1L;
        SocietyScoped theirs = () -> 2L;
        assertThatCode(() -> guard.assertSameSociety(mine)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.assertSameSociety(theirs)).isInstanceOf(TenantAccessException.class);
        assertThatThrownBy(() -> guard.assertSameSociety((SocietyScoped) null)).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void platformAdminsPassTheTenantCheck() {
        when(current.require()).thenReturn(user(PlatformRole.PLATFORM_ADMIN, null, null));
        assertThatCode(() -> guard.assertSameSociety(99L)).doesNotThrowAnyException();
    }

    @Test
    void anonymousUsersAreRefused() {
        when(current.require()).thenThrow(new TenantAccessException("You need to sign in"));
        assertThatThrownBy(() -> guard.assertSameSociety(1L)).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void aSocietyAdminIsLimitedToTheirOwnSocietyEvenWhenTheRequestNamesAnother() {
        User admin = user(PlatformRole.SOCIETY_ADMIN, 1L, null);
        when(current.require()).thenReturn(admin);
        assertThat(guard.societyIdForAdmin(null)).isEqualTo(1L);
        assertThat(guard.societyIdForAdmin(1L)).isEqualTo(1L);
        assertThatThrownBy(() -> guard.societyIdForAdmin(2L)).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void platformAdminsAndPlayersAreNotSocietyAdmins() {
        when(current.require()).thenReturn(user(PlatformRole.PLATFORM_ADMIN, null, null));
        assertThatThrownBy(() -> guard.requireSocietyAdmin()).isInstanceOf(TenantAccessException.class);
        when(current.require()).thenReturn(user(PlatformRole.SOCIETY_PLAYER, 1L, null));
        assertThatThrownBy(() -> guard.requireSocietyAdmin()).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void onlyPlatformAdminsPassRequirePlatformAdmin() {
        when(current.require()).thenReturn(user(PlatformRole.PLATFORM_ADMIN, null, null));
        assertThat(guard.requirePlatformAdmin()).isNotNull();
        when(current.require()).thenReturn(user(PlatformRole.SOCIETY_ADMIN, 1L, null));
        assertThatThrownBy(() -> guard.requirePlatformAdmin()).isInstanceOf(TenantAccessException.class);
    }

    @Test
    void personalDetailsRule() {
        when(current.orNull()).thenReturn(user(PlatformRole.SOCIETY_ADMIN, 1L, null));
        assertThat(guard.canSeePlayerPii(1L, 10L)).isTrue();
        assertThat(guard.canSeePlayerPii(2L, 10L)).isFalse();

        when(current.orNull()).thenReturn(user(PlatformRole.SOCIETY_PLAYER, 1L, 10L));
        assertThat(guard.canSeePlayerPii(1L, 10L)).isTrue();
        assertThat(guard.canSeePlayerPii(1L, 11L)).isFalse();

        when(current.orNull()).thenReturn(user(PlatformRole.PLATFORM_ADMIN, null, null));
        assertThat(guard.canSeePlayerPii(1L, 10L)).isFalse();

        when(current.orNull()).thenReturn(null);
        assertThat(guard.canSeePlayerPii(1L, 10L)).isFalse();
    }
}
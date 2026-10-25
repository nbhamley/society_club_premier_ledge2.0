package com.scpl.service;

import com.scpl.dto.PlayerProfileView;
import com.scpl.exception.NotFoundException;
import com.scpl.model.PlatformRole;
import com.scpl.model.Player;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** §14: contact details go only to the player and to admins of the player's own society, and the stripping happens in the service. */
class PlayerPrivacyTest {

    private PlayerRepository repo;
    private PlayerService service;
    private Player player;

    private static Society society(long id) {
        Society s = new Society();
        s.setId(id);
        return s;
    }

    private static User user(long id, PlatformRole role, Long societyId, Long playerId) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setUsername("u" + id);
        u.setEnabled(true);
        if (societyId != null) {
            u.setSociety(society(societyId));
        }
        if (playerId != null) {
            Player p = new Player();
            p.setId(playerId);
            u.setPlayer(p);
        }
        return u;
    }

    @BeforeEach
    void setUp() {
        repo = mock(PlayerRepository.class);
        service = new PlayerService(repo, mock(TeamRepository.class), mock(AuctionLotRepository.class),
                mock(SocietyRepository.class), mock(QuotaService.class), mock(AdminApprovalService.class), mock(AuditService.class));
        player = new Player();
        player.setId(10L);
        player.setSociety(society(1));
        player.setName("Asha Rao");
        player.setEmail("asha@x.example");
        player.setPhone("98200 00001");
        player.setDateOfBirth(LocalDate.of(1990, 5, 17));
        player.setAddress("Block A");
        player.setFlatNumber("A-101");
        player.setRuns(50);
        when(repo.findWithDetailsByIdAndSociety_Id(10L, 1L)).thenReturn(Optional.of(player));
    }

    @Test
    void whoMaySeePersonalDetails() {
        assertThat(PlayerService.canSeePii(user(1, PlatformRole.SOCIETY_ADMIN, 1L, null), player)).isTrue();
        assertThat(PlayerService.canSeePii(user(2, PlatformRole.SOCIETY_ADMIN, 2L, null), player)).isFalse();
        assertThat(PlayerService.canSeePii(user(3, PlatformRole.PLATFORM_ADMIN, null, null), player)).isFalse();
        assertThat(PlayerService.canSeePii(user(4, PlatformRole.SOCIETY_PLAYER, 1L, 10L), player)).isTrue();
        assertThat(PlayerService.canSeePii(user(5, PlatformRole.SOCIETY_PLAYER, 1L, 11L), player)).isFalse();
        assertThat(PlayerService.canSeePii(null, player)).isFalse();
        User disabled = user(6, PlatformRole.SOCIETY_ADMIN, 1L, null);
        disabled.setEnabled(false);
        assertThat(PlayerService.canSeePii(disabled, player)).isFalse();
    }

    @Test
    void theProfileIsStrippedOnTheServerForEveryoneNotEntitled() {
        for (User viewer : new User[]{null, user(2, PlatformRole.SOCIETY_ADMIN, 2L, null), user(3, PlatformRole.PLATFORM_ADMIN, null, null)}) {
            PlayerProfileView v = service.profile(viewer, 1L, 10L);
            assertThat(v.piiVisible()).isFalse();
            assertThat(v.name()).isEqualTo("Asha Rao");
            assertThat(v.runs()).isEqualTo(50);
            assertThat(v.email()).isNull();
            assertThat(v.phone()).isNull();
            assertThat(v.dateOfBirth()).isNull();
            assertThat(v.address()).isNull();
            assertThat(v.flatNumber()).isNull();
        }
    }

    @Test
    void thePlayerAndTheirSocietysAdminsGetTheDetails() {
        for (User viewer : new User[]{user(1, PlatformRole.SOCIETY_ADMIN, 1L, null), user(4, PlatformRole.SOCIETY_PLAYER, 1L, 10L)}) {
            PlayerProfileView v = service.profile(viewer, 1L, 10L);
            assertThat(v.piiVisible()).isTrue();
            assertThat(v.email()).isEqualTo("asha@x.example");
            assertThat(v.phone()).isEqualTo("98200 00001");
            assertThat(v.flatNumber()).isEqualTo("A-101");
        }
    }

    @Test
    void aDroppedPlayerIsInvisibleToThePublicButNotToTheirAdmins() {
        player.setActive(false);
        assertThatThrownBy(() -> service.profile(null, 1L, 10L)).isInstanceOf(NotFoundException.class);
        assertThat(service.profile(user(1, PlatformRole.SOCIETY_ADMIN, 1L, null), 1L, 10L).piiVisible()).isTrue();
    }
}
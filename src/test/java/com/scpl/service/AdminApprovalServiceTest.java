package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.TenantAccessException;
import com.scpl.model.AdminApproval;
import com.scpl.model.AdminApprovalVote;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.PlatformRole;
import com.scpl.model.Registration;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.User;
import com.scpl.model.Vote;
import com.scpl.repo.AdminApprovalRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The 3-of-N engine in isolation (acceptance 7 and 8). Threshold = min(3, admins - 1): the requester can never vote,
 * so a 3-admin society needs 2 approvals; from 4 admins up it needs 3.
 */
@ExtendWith(MockitoExtension.class)
class AdminApprovalServiceTest {

    private static final long APPROVAL_ID = 100L;

    @Mock
    AdminApprovalRepository approvals;
    @Mock
    SocietyRepository societies;
    @Mock
    UserRepository users;
    @Mock
    RegistrationRepository registrations;
    @Mock
    NotificationService notifications;
    @Mock
    AuditService audit;
    @Mock
    ApplicationEventPublisher publisher;

    private AdminApprovalService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AdminApprovalService(approvals, societies, users, registrations, notifications, audit, publisher, tm, 3);
    }

    // ------------------------------------------------------------------ helpers

    private static Society society(long id, int admins) {
        Society s = new Society();
        s.setId(id);
        s.setAdminCount(admins);
        s.setStatus(SocietyStatus.ACTIVE);
        return s;
    }

    private static User admin(long id, Society s) {
        User u = new User();
        u.setId(id);
        u.setUsername("admin" + id);
        u.setEmail("admin" + id + "@x.example");
        u.setRole(PlatformRole.SOCIETY_ADMIN);
        u.setSociety(s);
        u.setEnabled(true);
        return u;
    }

    private static AdminApproval pending(Society s, User requester, int eligible, int required, String type) {
        AdminApproval a = new AdminApproval();
        a.setId(APPROVAL_ID);
        a.setSociety(s);
        a.setEntityType(type);
        a.setEntityId(5L);
        a.setRequestedBy(requester);
        a.setRequestedAt(LocalDateTime.now());
        a.setEligibleVoters(eligible);
        a.setApprovalsRequired(required);
        return a;
    }

    private static void existingVote(AdminApproval a, User voter, Vote vote) {
        AdminApprovalVote v = new AdminApprovalVote();
        v.setApproval(a);
        v.setVoter(voter);
        v.setVote(vote);
        v.setVotedAt(LocalDateTime.now());
        a.getVotes().add(v);
    }

    private void stubVoting(AdminApproval a, User... voters) {
        lenient().when(approvals.findDetailedById(a.getId())).thenReturn(Optional.of(a));
        lenient().when(approvals.saveAndFlush(any(AdminApproval.class))).thenAnswer(i -> i.getArgument(0));
        for (User v : voters) {
            lenient().when(users.getReferenceById(v.getId())).thenReturn(v);
        }
    }

    private ApprovalDecidedEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return (ApprovalDecidedEvent) captor.getValue();
    }

    // ------------------------------------------------------------------ raising

    @ParameterizedTest
    @CsvSource({"3,2", "4,3", "5,3", "10,3"})
    void theThresholdNeverExceedsThePeopleWhoCanVote(int admins, int expectedRequired) {
        Society s = society(1, admins);
        User requester = admin(1, s);
        when(societies.findById(1L)).thenReturn(Optional.of(s));
        when(approvals.save(any(AdminApproval.class))).thenAnswer(i -> i.getArgument(0));

        AdminApproval a = service.raise(requester, 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "Drop X", null);

        assertThat(a.getApprovalsRequired()).isEqualTo(expectedRequired);
        assertThat(a.getEligibleVoters()).isEqualTo(admins);
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(notifications).notifyAdmins(eq(1L), eq(1L), anyString(), eq("Drop X"), anyString());
    }

    @Test
    void aSocietyThatCannotMuster_aSecondAdmin_cannotRaiseAnApproval() {
        Society s = society(1, 1);
        when(societies.findById(1L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.raise(admin(1, s), 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "x", null))
                .isInstanceOf(BusinessRuleException.class);
        verify(approvals, never()).save(any());
    }

    @Test
    void aSecondOpenApprovalForTheSameItemIsRefused() {
        Society s = society(1, 4);
        when(approvals.existsBySociety_IdAndEntityTypeAndEntityIdAndStatus(1L, ApprovalEntityType.PLAYER_DROP, 5L,
                ApprovalStatus.PENDING)).thenReturn(true);
        assertThatThrownBy(() -> service.raise(admin(1, s), 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "x", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already waiting");
    }

    @Test
    void onlyAnEnabledAdminOfThatSocietyMayRaiseOne() {
        Society s = society(1, 4);
        Society other = society(2, 4);
        User player = admin(7, s);
        player.setRole(PlatformRole.SOCIETY_PLAYER);
        assertThatThrownBy(() -> service.raise(player, 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "x", null))
                .isInstanceOf(TenantAccessException.class);
        assertThatThrownBy(() -> service.raise(admin(8, other), 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "x", null))
                .isInstanceOf(TenantAccessException.class);
        User disabled = admin(9, s);
        disabled.setEnabled(false);
        assertThatThrownBy(() -> service.raise(disabled, 1L, ApprovalEntityType.PLAYER_DROP, 5L, null, "x", null))
                .isInstanceOf(TenantAccessException.class);
    }

    @Test
    void anUnknownApprovalTypeIsRefused() {
        Society s = society(1, 4);
        assertThatThrownBy(() -> service.raise(admin(1, s), 1L, "LAUNCH_ROCKET", 5L, null, "x", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Unknown approval type");
    }

    // ------------------------------------------------------------------ voting

    @Test
    void theRequesterCannotVoteOnTheirOwnApproval() {
        Society s = society(1, 4);
        User requester = admin(1, s);
        AdminApproval a = pending(s, requester, 4, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, requester);

        assertThatThrownBy(() -> service.castVote(requester, APPROVAL_ID, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("raised an approval");
        verify(approvals, never()).saveAndFlush(any());
    }

    @Test
    void anAdminCannotVoteTwice() {
        Society s = society(1, 4);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, a1, 4, 3, ApprovalEntityType.PLAYER_DROP);
        existingVote(a, a2, Vote.APPROVE);
        stubVoting(a, a2);

        assertThatThrownBy(() -> service.castVote(a2, APPROVAL_ID, Vote.REJECT, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already voted");
    }

    @Test
    void anAdminOfAnotherSocietyCannotVote() {
        Society s = society(1, 4);
        User outsider = admin(50, society(2, 4));
        AdminApproval a = pending(s, admin(1, s), 4, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, outsider);
        assertThatThrownBy(() -> service.castVote(outsider, APPROVAL_ID, Vote.APPROVE, null))
                .isInstanceOf(TenantAccessException.class);
    }

    @Test
    void aDecidedApprovalAcceptsNoMoreVotes() {
        Society s = society(1, 4);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, admin(1, s), 4, 3, ApprovalEntityType.PLAYER_DROP);
        a.setStatus(ApprovalStatus.APPROVED);
        stubVoting(a, a2);
        assertThatThrownBy(() -> service.castVote(a2, APPROVAL_ID, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already approved");
    }

    @Test
    void anUnknownApprovalIsNotFound() {
        Society s = society(1, 4);
        when(approvals.findDetailedById(APPROVAL_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.castVote(admin(2, s), APPROVAL_ID, Vote.APPROVE, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theLastRequiredApprovalApprovesAndPublishesTheEvent() {
        Society s = society(1, 4);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        User a3 = admin(3, s);
        User a4 = admin(4, s);
        AdminApproval a = pending(s, a1, 4, 3, ApprovalEntityType.PLAYER_DROP);
        existingVote(a, a2, Vote.APPROVE);
        existingVote(a, a3, Vote.APPROVE);
        stubVoting(a, a4);

        AdminApproval result = service.castVote(a4, APPROVAL_ID, Vote.APPROVE, "ok");

        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(result.getDecidedAt()).isNotNull();
        assertThat(result.getDecisionNotes()).contains("3 approve");
        ApprovalDecidedEvent event = publishedEvent();
        assertThat(event.approval().getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(event.decidedBy()).isSameAs(a4);
    }

    @Test
    void anApprovalBelowTheThresholdStaysPendingAndPublishesNothing() {
        Society s = society(1, 4);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, admin(1, s), 4, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, a2);

        AdminApproval result = service.castVote(a2, APPROVAL_ID, Vote.APPROVE, null);

        assertThat(result.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verifyNoInteractions(publisher);
    }

    @Test
    void inAFiveAdminSocietyTwoRejectionsAutoReject() {
        Society s = society(1, 5);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        User a3 = admin(3, s);
        AdminApproval a = pending(s, a1, 5, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, a2, a3);

        assertThat(service.castVote(a2, APPROVAL_ID, Vote.REJECT, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verifyNoInteractions(publisher);

        assertThat(service.castVote(a3, APPROVAL_ID, Vote.REJECT, null).getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(publishedEvent().approval().getStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void inAThreeAdminSocietyASingleRejectionIsEnoughBecauseOnlyTwoCanVote() {
        Society s = society(1, 3);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, admin(1, s), 3, 2, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, a2);
        assertThat(service.castVote(a2, APPROVAL_ID, Vote.REJECT, null).getStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void aFiveAdminSocietyNeedsThreeApprovals() {
        Society s = society(1, 5);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        User a3 = admin(3, s);
        User a4 = admin(4, s);
        AdminApproval a = pending(s, a1, 5, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a, a2, a3, a4);

        assertThat(service.castVote(a2, APPROVAL_ID, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(service.castVote(a3, APPROVAL_ID, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(service.castVote(a4, APPROVAL_ID, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void anAdminWhoIsInTheRegistrationBatchCannotDecideIt() {
        Society s = society(1, 4);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, a1, 4, 3, ApprovalEntityType.REGISTRATION);
        Registration own = new Registration();
        own.setEmail(a2.getEmail());
        when(registrations.findByApproval_Id(APPROVAL_ID)).thenReturn(List.of(own));
        stubVoting(a, a2);

        assertThatThrownBy(() -> service.castVote(a2, APPROVAL_ID, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("one of the people");
    }

    @Test
    void anAdminNotInTheRegistrationBatchMayVoteOnIt() {
        Society s = society(1, 4);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, admin(1, s), 4, 3, ApprovalEntityType.REGISTRATION);
        Registration someoneElse = new Registration();
        someoneElse.setEmail("someone.else@x.example");
        when(registrations.findByApproval_Id(APPROVAL_ID)).thenReturn(List.of(someoneElse));
        stubVoting(a, a2);

        assertThat(service.castVote(a2, APPROVAL_ID, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    // ------------------------------------------------------------------ withdrawing

    @Test
    void onlyTheRequesterCanWithdraw() {
        Society s = society(1, 4);
        User a1 = admin(1, s);
        User a2 = admin(2, s);
        AdminApproval a = pending(s, a1, 4, 3, ApprovalEntityType.PLAYER_DROP);
        stubVoting(a);

        assertThatThrownBy(() -> service.withdraw(a2, APPROVAL_ID)).isInstanceOf(BusinessRuleException.class);

        AdminApproval withdrawn = service.withdraw(a1, APPROVAL_ID);
        assertThat(withdrawn.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(publishedEvent().approval().getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
    }
}
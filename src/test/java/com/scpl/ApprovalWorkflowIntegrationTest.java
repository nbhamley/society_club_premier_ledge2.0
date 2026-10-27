package com.scpl;

import com.scpl.dto.InviteTicket;
import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.RegistrationRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.model.AdminApproval;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.PlayerRole;
import com.scpl.model.Registration;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.User;
import com.scpl.model.Vote;
import com.scpl.repo.AdminApprovalRepository;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.AuditLogRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.service.AdminApprovalService;
import com.scpl.service.AuditActions;
import com.scpl.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acceptance 7, 8, 9, 17: the approval flow end to end with real services, in a society of its own. */
class ApprovalWorkflowIntegrationTest extends TenantFixtures {

    @Autowired
    RegistrationService registrationService;
    @Autowired
    AdminApprovalService approvalService;
    @Autowired
    SeasonRepository seasons;
    @Autowired
    SocietyRepository societies;
    @Autowired
    RegistrationRepository registrations;
    @Autowired
    PlayerRepository players;
    @Autowired
    AuctionLotRepository lots;
    @Autowired
    AuditLogRepository auditLogs;
    @Autowired
    AdminApprovalRepository approvalRepo;

    private Season openSeason(Long societyId) {
        Season s = new Season();
        s.setSociety(societies.getReferenceById(societyId));
        s.setName("Open Season");
        s.setStatus(SeasonStatus.REGISTRATION_OPEN);
        s.setRegistrationOpen(true);
        return seasons.save(s);
    }

    private void register(Long societyId, Long seasonId, String tag, int index) {
        registrationService.submit(societyId, seasonId, new RegistrationRequest("Player " + index,
                "p" + index + "." + tag + "@wf.example", null, null, null, PlayerRole.BATSMAN, null, null, null));
    }

    @Test
    void bulkApproveRaisesOneApprovalAndOneOtherAdminsApprovalCreatesPlayersAndLots() {
        Fixture fx = newSociety(4);
        Long sid = fx.id();
        User a1 = fx.admins().get(0);
        User a2 = fx.admins().get(1);
        User a3 = fx.admins().get(2);
        Season season = openSeason(sid);
        for (int i = 1; i <= 10; i++) {
            register(sid, season.getId(), fx.society().getSlug(), i);
        }
        List<Long> ids = registrations.findBySociety_IdOrderByCreatedAtDesc(sid).stream().map(Registration::getId).toList();
        assertThat(ids).hasSize(10);

        Optional<AdminApproval> raised = registrationService.bulkAction(a1, sid, ids, "APPROVE");
        assertThat(raised).isPresent();
        Long approvalId = raised.get().getId();
        assertThat(raised.get().getApprovalsRequired()).isEqualTo(1);              // registrations need one approval
        assertThat(approvalRepo.countBySociety_IdAndStatus(sid, ApprovalStatus.PENDING)).isEqualTo(1);   // ONE approval
        assertThat(registrations.findByApproval_Id(approvalId)).hasSize(10);                             // wrapping 10 ids
        assertThat(players.countBySociety_IdAndActiveTrue(sid)).isZero();                                // nothing created yet

        assertThatThrownBy(() -> approvalService.castVote(a1, approvalId, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("raised an approval");

        assertThat(approvalService.castVote(a2, approvalId, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThatThrownBy(() -> approvalService.castVote(a3, approvalId, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already approved");

        assertThat(players.countBySociety_IdAndActiveTrue(sid)).isEqualTo(10);
        assertThat(lots.countBySociety_Id(sid)).isEqualTo(10);
        assertThat(registrations.countBySociety_IdAndStatus(sid, RegistrationStatus.APPROVED)).isEqualTo(10);
        assertThat(auditLogs.countBySocietyIdAndAction(sid, AuditActions.REGISTRATION_APPROVED)).isEqualTo(10);
        assertThat(auditLogs.countBySocietyIdAndAction(sid, AuditActions.APPROVAL_APPROVED)).isEqualTo(1);
    }

    @Test
    void aRejectedApprovalReturnsTheRegistrationToThePendingQueue() {
        Fixture fx = newSociety(4);
        Long sid = fx.id();
        Season season = openSeason(sid);
        register(sid, season.getId(), fx.society().getSlug(), 1);
        Long registrationId = registrations.findBySociety_IdOrderByCreatedAtDesc(sid).get(0).getId();

        AdminApproval approval = registrationService.requestApproval(fx.admins().get(0), sid, registrationId);
        // 4 admins: three can vote and one approval is enough, so it is rejected only once all three reject
        Long id = approval.getId();
        assertThat(approvalService.castVote(fx.admins().get(1), id, Vote.REJECT, "not eligible").getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalService.castVote(fx.admins().get(2), id, Vote.REJECT, null).getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        AdminApproval after = approvalService.castVote(fx.admins().get(3), id, Vote.REJECT, null);
        assertThat(after.getStatus()).isEqualTo(ApprovalStatus.REJECTED);

        Registration r = registrations.findByIdAndSociety_Id(registrationId, sid).orElseThrow();
        assertThat(r.getStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(r.getApproval()).isNull();
        assertThat(players.countBySociety_IdAndActiveTrue(sid)).isZero();
    }

    @Test
    void aNewAdminIsCreatedOnlyAfterThreeDistinctAdminsApprove() {
        Fixture fx = newSociety(5);
        Long sid = fx.id();
        String username = "newadmin." + fx.society().getSlug();
        InviteTicket ticket = societyService.requestAdminInvite(fx.admins().get(0), sid,
                new NewAdminRequest(username, username + "@wf.example", "New Admin", null, null));
        Long id = ticket.approvalId();
        assertThat(approvalRepo.findById(id).orElseThrow().getApprovalsRequired()).isEqualTo(3);

        assertThat(approvalService.castVote(fx.admins().get(1), id, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThatThrownBy(() -> approvalService.castVote(fx.admins().get(1), id, Vote.APPROVE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already voted");
        assertThat(approvalService.castVote(fx.admins().get(2), id, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(users.findByUsernameIgnoreCase(username)).isEmpty();          // two approvals: still blocked

        assertThat(approvalService.castVote(fx.admins().get(3), id, Vote.APPROVE, null).getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(users.findByUsernameIgnoreCase(username)).get().extracting(User::isSocietyAdmin).isEqualTo(true);
        assertThat(societies.findById(sid).orElseThrow().getAdminCount()).isEqualTo(6);
    }
}

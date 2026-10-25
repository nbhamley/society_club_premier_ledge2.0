package com.scpl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.dto.InviteTicket;
import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminApproval;
import com.scpl.model.AdminContact;
import com.scpl.model.ApprovalEntityType;
import com.scpl.model.ApprovalStatus;
import com.scpl.model.PlatformRole;
import com.scpl.model.Society;
import com.scpl.model.SocietyStatus;
import com.scpl.model.User;
import com.scpl.repo.AdminContactRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UserRepository;
import com.scpl.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SocietyService {

    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SocietyRepository societies;
    private final UserRepository users;
    private final AdminContactRepository contacts;
    private final AdminApprovalService approvals;
    private final AuditService audit;
    private final NotificationService notifications;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper;

    @Value("${scpl.approval.min-admins-to-activate:3}")
    private int minAdmins;

    // ------------------------------------------------------------------ reads

    /** Public pages only ever show ACTIVE societies; DRAFT and SUSPENDED look like they do not exist. */
    @Transactional(readOnly = true)
    public Society getPublicBySlug(String slug) {
        return societies.findBySlug(slug).filter(Society::isActive)
                .orElseThrow(() -> new NotFoundException("Society not found"));
    }

    @Transactional(readOnly = true)
    public Society get(Long societyId) {
        return societies.findById(societyId).orElseThrow(() -> new NotFoundException("Society not found"));
    }

    @Transactional(readOnly = true)
    public List<Society> listAll() {
        return societies.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<User> adminsOf(Long societyId) {
        return users.findBySociety_IdAndRoleOrderByFullNameAsc(societyId, PlatformRole.SOCIETY_ADMIN);
    }

    // ------------------------------------------------------------------ creation and lifecycle

    /**
     * Creates a society in DRAFT with its founding admins. Founding admins are created directly
     * because an approval needs existing admins to vote. After founding, every admin change goes
     * through an approval. Activation is a separate step so the minimum-admins rule has one home.
     *
     * @param actor the platform admin, or null for the public self-service /subscribe flow
     */
    @Transactional
    public Society createWithAdmins(SocietyRequest req, List<NewAdminRequest> admins, User actor) {
        Society s = new Society();
        applyDetails(s, req);
        s.setSlug(uniqueSlug(Text.clean(req.slug()) != null ? req.slug() : req.name()));
        s.setStatus(SocietyStatus.DRAFT);
        s = societies.save(s);

        for (NewAdminRequest a : admins) {
            if (Text.clean(a.password()) == null) {
                throw new BusinessRuleException("A password is required for admin " + a.username());
            }
            addAdminWithHash(s, a.username(), a.email(), a.fullName(), a.phone(), passwordEncoder.encode(a.password()));
        }
        audit.record(actor, s.getId(), AuditActions.SOCIETY_CREATED, "SOCIETY", s.getId(), null,
                Map.of("name", s.getName(), "slug", s.getSlug()));
        return s;
    }

    @Transactional
    public Society activate(Long societyId, User actor) {
        Tenancy.requirePlatformAdmin(actor);
        Society s = get(societyId);
        recalcAdminCount(s);
        if (!s.hasMinimumAdmins(minAdmins)) {
            throw new BusinessRuleException("A society needs at least " + minAdmins
                    + " admins before it can be activated (it has " + s.getAdminCount() + ")");
        }
        SocietyStatus before = s.getStatus();
        s.setStatus(SocietyStatus.ACTIVE);
        societies.save(s);
        audit.record(actor, societyId, AuditActions.SOCIETY_ACTIVATED, "SOCIETY", societyId,
                Map.of("status", before.name()), Map.of("status", "ACTIVE"));
        return s;
    }

    @Transactional
    public Society suspend(Long societyId, User actor) {
        Tenancy.requirePlatformAdmin(actor);
        Society s = get(societyId);
        SocietyStatus before = s.getStatus();
        s.setStatus(SocietyStatus.SUSPENDED);
        societies.save(s);
        audit.record(actor, societyId, AuditActions.SOCIETY_SUSPENDED, "SOCIETY", societyId,
                Map.of("status", before.name()), Map.of("status", "SUSPENDED"));
        return s;
    }

    @Transactional
    public Society updateDetails(User actor, Long societyId, SocietyRequest req) {
        Tenancy.requireAdminOf(actor, societyId);
        Society s = get(societyId);
        Map<String, Object> before = Map.of("name", s.getName(), "slug", s.getSlug());
        applyDetails(s, req);
        String requested = Text.clean(req.slug());
        if (requested != null) {
            String slug = Text.slugify(requested);
            // An explicit slug edit is rejected on a clash instead of silently suffixed:
            // the admin chose that URL on purpose.
            if (societies.existsBySlugAndIdNot(slug, societyId)) {
                throw new BusinessRuleException("The address /society/" + slug + " is already taken");
            }
            s.setSlug(slug);
        }
        societies.save(s);
        audit.record(actor, societyId, AuditActions.SOCIETY_UPDATED, "SOCIETY", societyId, before,
                Map.of("name", s.getName(), "slug", s.getSlug()));
        return s;
    }

    // ------------------------------------------------------------------ admin team

    /** Direct creation, used by the seeder and tests. Normal growth goes through requestAdminInvite. */
    @Transactional
    public User addAdmin(Long societyId, NewAdminRequest req) {
        if (Text.clean(req.password()) == null) {
            throw new BusinessRuleException("A password is required for admin " + req.username());
        }
        return addAdminWithHash(get(societyId), req.username(), req.email(), req.fullName(), req.phone(),
                passwordEncoder.encode(req.password()));
    }

    @Transactional
    public InviteTicket requestAdminInvite(User actor, Long societyId, NewAdminRequest req) {
        Tenancy.requireAdminOf(actor, societyId);
        if (users.existsByUsernameIgnoreCase(req.username())) {
            throw new BusinessRuleException("That username is already taken");
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new BusinessRuleException("That email is already registered");
        }
        String temporary = randomPassword();
        Map<String, String> payload = new HashMap<>();
        payload.put("username", req.username().trim());
        payload.put("email", req.email().trim());
        payload.put("fullName", Text.clean(req.fullName()));
        payload.put("phone", Text.clean(req.phone()));
        payload.put("passwordHash", passwordEncoder.encode(temporary));

        AdminApproval a = approvals.raise(actor, societyId, ApprovalEntityType.ADMIN_INVITE, null, null,
                "Invite " + req.username().trim() + " as a society admin", toJson(payload));
        return new InviteTicket(a.getId(), temporary);
    }

    @Transactional
    public AdminApproval requestAdminRemoval(User actor, Long societyId, Long targetUserId) {
        Tenancy.requireAdminOf(actor, societyId);
        if (actor.getId().equals(targetUserId)) {
            throw new BusinessRuleException("You cannot remove yourself. Ask another admin to raise the request.");
        }
        User target = users.findByIdAndSociety_Id(targetUserId, societyId)
                .filter(User::isSocietyAdmin)
                .orElseThrow(() -> new NotFoundException("Admin not found"));
        Society s = get(societyId);
        if (s.isActive() && s.getAdminCount() - 1 < minAdmins) {
            throw new BusinessRuleException("An active society must keep at least " + minAdmins + " admins");
        }
        return approvals.raise(actor, societyId, ApprovalEntityType.ADMIN_REMOVE, target.getId(), null,
                "Remove " + (target.getFullName() != null ? target.getFullName() : target.getUsername())
                        + " from the admin team", null);
    }

    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        AdminApproval a = event.approval();
        if (a.getStatus() != ApprovalStatus.APPROVED) {
            return;
        }
        if (ApprovalEntityType.ADMIN_INVITE.equals(a.getEntityType())) {
            executeInvite(a, event.decidedBy());
        } else if (ApprovalEntityType.ADMIN_REMOVE.equals(a.getEntityType())) {
            executeRemoval(a, event.decidedBy());
        }
    }

    private void executeInvite(AdminApproval a, User decidedBy) {
        Map<String, String> p = fromJson(a.getPayload());
        User created = addAdminWithHash(get(a.getSocietyId()), p.get("username"), p.get("email"),
                p.get("fullName"), p.get("phone"), p.get("passwordHash"));
        audit.record(decidedBy, a.getSocietyId(), AuditActions.ADMIN_ADDED, "USER", created.getId(), null,
                Map.of("username", created.getUsername()));
    }

    private void executeRemoval(AdminApproval a, User decidedBy) {
        Society s = get(a.getSocietyId());
        User target = users.findByIdAndSociety_Id(a.getEntityId(), a.getSocietyId())
                .orElseThrow(() -> new NotFoundException("Admin not found"));
        // Re-check at execution time: other admins may have been removed since the request.
        if (s.isActive() && s.getAdminCount() - 1 < minAdmins) {
            throw new BusinessRuleException("An active society must keep at least " + minAdmins + " admins");
        }
        // The row is kept (votes and audit entries reference it). A linked player keeps a
        // player login; anyone else is disabled.
        if (target.getPlayer() != null) {
            target.setRole(PlatformRole.SOCIETY_PLAYER);
        } else {
            target.setEnabled(false);
        }
        contacts.findBySociety_IdOrderByNameAsc(s.getId()).stream()
                .filter(c -> c.getUser() != null && c.getUser().getId().equals(target.getId()))
                .forEach(contacts::delete);
        users.save(target);
        recalcAdminCount(s);
        audit.record(decidedBy, s.getId(), AuditActions.ADMIN_REMOVED, "USER", target.getId(),
                Map.of("username", target.getUsername()), null);
    }

    // ------------------------------------------------------------------ internals

    private User addAdminWithHash(Society s, String username, String email, String fullName, String phone,
                                  String passwordHash) {
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new BusinessRuleException("The username '" + username + "' is already taken");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException("The email '" + email + "' is already registered");
        }
        User u = new User();
        u.setUsername(username.trim());
        u.setEmail(email.trim());
        u.setFullName(Text.clean(fullName));
        u.setPhone(Text.clean(phone));
        u.setPasswordHash(passwordHash);
        u.setRole(PlatformRole.SOCIETY_ADMIN);
        u.setSociety(s);
        u.setEnabled(true);
        u = users.save(u);

        AdminContact card = new AdminContact();
        card.setSociety(s);
        card.setUser(u);
        card.setName(u.getFullName() != null ? u.getFullName() : u.getUsername());
        card.setRoleTitle("Admin");
        card.setPhone(u.getPhone());
        card.setWhatsappNumber(u.getPhone());
        card.setEmail(u.getEmail());
        contacts.save(card);

        recalcAdminCount(s);
        return u;
    }

    /** Counts enabled admins only, so a disabled ex-admin never inflates the voting pool. */
    private void recalcAdminCount(Society s) {
        s.setAdminCount(users.findBySociety_IdAndRoleAndEnabledTrue(s.getId(), PlatformRole.SOCIETY_ADMIN).size());
        societies.save(s);
    }

    private void applyDetails(Society s, SocietyRequest r) {
        s.setName(r.name().trim());
        s.setDescription(Text.clean(r.description()));
        s.setCity(Text.clean(r.city()));
        s.setAddress(Text.clean(r.address()));
        s.setContactEmail(Text.clean(r.contactEmail()));
        s.setContactPhone(Text.clean(r.contactPhone()));
    }

    private String uniqueSlug(String source) {
        String base = Text.slugify(source);
        String candidate = base;
        int n = 2;
        while (societies.existsBySlug(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String toJson(Map<String, String> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise approval payload", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt approval payload", e);
        }
    }
}
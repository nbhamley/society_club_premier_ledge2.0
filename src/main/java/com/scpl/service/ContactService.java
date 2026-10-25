package com.scpl.service;

import com.scpl.dto.ContactRequest;
import com.scpl.dto.ContactViews.AdminMemberView;
import com.scpl.dto.ContactViews.ContactView;
import com.scpl.dto.ContactViews.ContactsPageView;
import com.scpl.dto.VendorView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.AdminContact;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.repo.AdminContactRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.util.Text;
import com.scpl.util.Urls;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Admin-team contact cards (§11). Cards that belong to an admin login are created and removed together with
 * the login (SocietyService), so they cannot be deleted here; this service manages the extra committee cards.
 */
@Service
@RequiredArgsConstructor
public class ContactService {

    private final AdminContactRepository contacts;
    private final SocietyRepository societies;
    private final SocietyService societyService;
    private final VendorService vendors;
    private final QuotaService quota;
    private final AuditService audit;

    @Value("${scpl.default-country-code:91}")
    private String countryCode;

    @Transactional(readOnly = true)
    public ContactsPageView page(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        Society society = societyService.get(societyId);
        String me = actor.getFullName() != null && !actor.getFullName().isBlank() ? actor.getFullName() : actor.getUsername();

        List<AdminMemberView> admins = societyService.adminsOf(societyId).stream()
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
                .map(u -> new AdminMemberView(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getPhone(),
                        u.getPlayerId() != null, u.getId().equals(actor.getId()), !u.getId().equals(actor.getId())))
                .toList();
        List<ContactView> cards = contacts.findBySociety_IdOrderByNameAsc(societyId).stream().map(c -> {
            String message = "Hi " + c.getName() + ", this is " + me + " from " + society.getName();
            String number = c.getWhatsappNumber() != null && !c.getWhatsappNumber().isBlank() ? c.getWhatsappNumber() : c.getPhone();
            return new ContactView(c.getId(), c.getName(), c.getRoleTitle(), c.getPhone(), c.getWhatsappNumber(), c.getEmail(),
                    c.getUser() != null, Urls.whatsapp(number, countryCode, message), Urls.tel(c.getPhone()),
                    c.getEmail() == null ? null : "mailto:" + c.getEmail());
        }).toList();

        // Check the plan first: catching QuotaExceededException here would poison this transaction.
        boolean vendorModule = quota.limitsFor(societyId).vendorModule();
        List<VendorView> vendorRows = vendorModule ? vendors.list(actor, societyId, null) : List.of();
        return new ContactsPageView(admins, cards, vendorRows, vendorModule, society.getAdminCount());
    }

    @Transactional
    public void add(User actor, Long societyId, ContactRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        AdminContact c = new AdminContact();
        c.setSociety(societies.getReferenceById(societyId));
        c.setName(r.name().trim());
        c.setRoleTitle(Text.clean(r.roleTitle()));
        c.setPhone(Text.clean(r.phone()));
        c.setWhatsappNumber(Text.clean(r.whatsappNumber()));
        c.setEmail(Text.clean(r.email()));
        c = contacts.save(c);
        audit.record(actor, societyId, AuditActions.CONTACT_ADDED, "CONTACT", c.getId(), null, Map.of("name", c.getName()));
    }

    @Transactional
    public void delete(User actor, Long societyId, Long contactId) {
        Tenancy.requireAdminOf(actor, societyId);
        AdminContact c = contacts.findByIdAndSociety_Id(contactId, societyId)
                .orElseThrow(() -> new NotFoundException("Contact not found"));
        if (c.getUser() != null) {
            throw new BusinessRuleException("This card belongs to an admin login. Remove the admin through an approval instead.");
        }
        contacts.delete(c);
        audit.record(actor, societyId, AuditActions.CONTACT_DELETED, "CONTACT", contactId, Map.of("name", c.getName()), null);
    }
}
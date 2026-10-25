package com.scpl.service;

import com.scpl.dto.VendorRequest;
import com.scpl.dto.VendorView;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.util.Text;
import com.scpl.util.Urls;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-society vendor directory (§11). Standard plan and above; never visible outside the admin team. */
@Service
@RequiredArgsConstructor
public class VendorService {

    private final VendorContactRepository vendors;
    private final VendorBillRepository bills;
    private final SocietyRepository societies;
    private final QuotaService quota;
    private final AuditService audit;

    @Value("${scpl.default-country-code:91}")
    private String countryCode;

    @Transactional(readOnly = true)
    public List<VendorView> list(User actor, Long societyId, VendorCategory categoryOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        Society society = societies.findById(societyId).orElseThrow(() -> new NotFoundException("Society not found"));
        Map<Long, Double> spend = new HashMap<>();
        bills.spendByVendor(societyId).forEach(r -> spend.put(r.getVendorId(), r.getTotal()));
        List<VendorContact> rows = categoryOrNull == null
                ? vendors.findBySociety_IdOrderByNameAsc(societyId)
                : vendors.findBySociety_IdAndCategoryOrderByNameAsc(societyId, categoryOrNull);
        return rows.stream().map(v -> toView(v, society, spend.getOrDefault(v.getId(), 0.0))).toList();
    }

    @Transactional(readOnly = true)
    public VendorContact get(User actor, Long societyId, Long vendorId) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        return find(societyId, vendorId);
    }

    @Transactional
    public VendorContact create(User actor, Long societyId, VendorRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        if (vendors.existsBySociety_IdAndNameIgnoreCase(societyId, r.name().trim())) {
            throw new BusinessRuleException("A vendor named '" + r.name().trim() + "' already exists");
        }
        VendorContact v = new VendorContact();
        v.setSociety(societies.getReferenceById(societyId));
        apply(v, r);
        v = vendors.save(v);
        audit.record(actor, societyId, AuditActions.VENDOR_CREATED, "VENDOR", v.getId(), null,
                Map.of("name", v.getName(), "category", v.getCategory().name()));
        return v;
    }

    @Transactional
    public VendorContact update(User actor, Long societyId, Long vendorId, VendorRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorContact v = find(societyId, vendorId);
        String newName = r.name().trim();
        if (!newName.equalsIgnoreCase(v.getName()) && vendors.existsBySociety_IdAndNameIgnoreCase(societyId, newName)) {
            throw new BusinessRuleException("A vendor named '" + newName + "' already exists");
        }
        Map<String, Object> before = Map.of("name", v.getName(), "active", v.getActive());
        apply(v, r);
        vendors.save(v);
        audit.record(actor, societyId, AuditActions.VENDOR_UPDATED, "VENDOR", vendorId, before,
                Map.of("name", v.getName(), "active", v.getActive()));
        return v;
    }

    @Transactional
    public void delete(User actor, Long societyId, Long vendorId) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorContact v = find(societyId, vendorId);
        // Bills cascade-delete with their vendor, which would silently erase spend history.
        if (!bills.findByVendor_Id(vendorId).isEmpty()) {
            throw new BusinessRuleException("This vendor has bills on record. Mark it inactive instead of deleting it.");
        }
        vendors.delete(v);
        audit.record(actor, societyId, AuditActions.VENDOR_DELETED, "VENDOR", vendorId, Map.of("name", v.getName()), null);
    }

    // ------------------------------------------------------------------ internals

    private VendorContact find(Long societyId, Long vendorId) {
        return vendors.findByIdAndSociety_Id(vendorId, societyId)
                .orElseThrow(() -> new NotFoundException("Vendor not found"));
    }

    private void apply(VendorContact v, VendorRequest r) {
        if (r.rating() != null && (r.rating() < 1 || r.rating() > 5)) {
            throw new BusinessRuleException("Rating must be between 1 and 5");
        }
        v.setName(r.name().trim());
        v.setCategory(r.category() == null ? VendorCategory.OTHER : r.category());
        v.setContactPerson(Text.clean(r.contactPerson()));
        v.setPhone(Text.clean(r.phone()));
        v.setWhatsappNumber(Text.clean(r.whatsappNumber()));
        v.setEmail(Text.clean(r.email()));
        v.setAddress(Text.clean(r.address()));
        v.setCity(Text.clean(r.city()));
        v.setWebsite(Urls.requireWebUrl(r.website(), "Website"));
        v.setRating(r.rating());
        v.setNotes(Text.clean(r.notes()));
        v.setActive(r.active() == null || r.active());
    }

    private VendorView toView(VendorContact v, Society society, double billTotal) {
        String message = "Hi " + v.getName() + ", reaching out from " + society.getName()
                + " re: " + v.getCategory().getLabel();
        String number = v.getWhatsappNumber() != null && !v.getWhatsappNumber().isBlank()
                ? v.getWhatsappNumber() : v.getPhone();
        return new VendorView(v.getId(), v.getName(), v.getCategory(), v.getCategory().getLabel(),
                v.getContactPerson(), v.getPhone(), v.getWhatsappNumber(), v.getEmail(), v.getAddress(),
                v.getCity(), v.getWebsite(), v.getRating(), v.getNotes(), Boolean.TRUE.equals(v.getActive()),
                billTotal, Urls.whatsapp(number, countryCode, message), Urls.tel(v.getPhone()),
                v.getEmail() == null ? null : "mailto:" + v.getEmail());
    }
}
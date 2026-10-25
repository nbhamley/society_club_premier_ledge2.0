package com.scpl.service;

import com.scpl.dto.BillRequest;
import com.scpl.dto.BudgetSummary;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.BillStatus;
import com.scpl.model.Season;
import com.scpl.model.User;
import com.scpl.model.VendorBill;
import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.UserRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.util.Text;
import com.scpl.util.Urls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Vendor bills and the per-season budget dashboard (§6, §11). Visible only to the owning admin team (§14). */
@Service
@RequiredArgsConstructor
public class BillService {

    private static final double MAX_AMOUNT = 1_000_000_000.0;

    private final VendorBillRepository bills;
    private final VendorContactRepository vendors;
    private final SeasonRepository seasons;
    private final SocietyRepository societies;
    private final UserRepository users;
    private final QuotaService quota;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<VendorBill> list(User actor, Long societyId, Long seasonIdOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        return seasonIdOrNull == null
                ? bills.findBySociety_IdOrderByBillDateDesc(societyId)
                : bills.findBySociety_IdAndSeason_IdOrderByBillDateDesc(societyId, seasonIdOrNull);
    }

    @Transactional
    public VendorBill create(User actor, Long societyId, BillRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorBill b = new VendorBill();
        b.setSociety(societies.getReferenceById(societyId));
        b.setStatus(BillStatus.PENDING);
        apply(b, societyId, r);
        b = bills.save(b);
        audit.record(actor, societyId, AuditActions.BILL_CREATED, "BILL", b.getId(), null,
                Map.of("title", b.getTitle(), "amount", b.getAmount()));
        return b;
    }

    @Transactional
    public VendorBill update(User actor, Long societyId, Long billId, BillRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorBill b = find(societyId, billId);
        Map<String, Object> before = Map.of("title", b.getTitle(), "amount", b.getAmount());
        apply(b, societyId, r);
        bills.save(b);
        audit.record(actor, societyId, AuditActions.BILL_UPDATED, "BILL", billId, before,
                Map.of("title", b.getTitle(), "amount", b.getAmount()));
        return b;
    }

    /** PENDING, PAID and DISPUTED can move freely. PAID records the payment date and who recorded it. */
    @Transactional
    public VendorBill setStatus(User actor, Long societyId, Long billId, BillStatus status) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorBill b = find(societyId, billId);
        BillStatus before = b.getStatus();
        if (before == status) {
            return b;
        }
        b.setStatus(status);
        if (status == BillStatus.PAID) {
            b.setPaidDate(LocalDate.now());
            b.setApprovedBy(users.getReferenceById(actor.getId()));
        } else {
            b.setPaidDate(null);
            b.setApprovedBy(null);
        }
        bills.save(b);
        audit.record(actor, societyId, AuditActions.BILL_STATUS_CHANGED, "BILL", billId,
                Map.of("status", before.name()), Map.of("status", status.name()));
        return b;
    }

    @Transactional
    public void delete(User actor, Long societyId, Long billId) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        VendorBill b = find(societyId, billId);
        bills.delete(b);
        audit.record(actor, societyId, AuditActions.BILL_DELETED, "BILL", billId,
                Map.of("title", b.getTitle(), "amount", b.getAmount(), "status", b.getStatus().name()), null);
    }

    /**
     * Spend against plan. The plan is one figure per season (Season.plannedBudget), so the dashboard
     * compares category actuals against that overall figure, not per-category targets.
     * With no season it covers all seasons and sums their plans.
     */
    @Transactional(readOnly = true)
    public BudgetSummary budget(User actor, Long societyId, Long seasonIdOrNull) {
        Tenancy.requireAdminOf(actor, societyId);
        quota.assertVendorModule(societyId);
        Season season = seasonIdOrNull == null ? null : seasons.findByIdAndSociety_Id(seasonIdOrNull, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));

        List<VendorBill> all = season == null
                ? bills.findBySociety_IdOrderByBillDateDesc(societyId)
                : bills.findBySociety_IdAndSeason_IdOrderByBillDateDesc(societyId, season.getId());
        double paid = 0;
        double pending = 0;
        double disputed = 0;
        for (VendorBill b : all) {
            switch (b.getStatus()) {
                case PAID -> paid += b.getAmount();
                case PENDING -> pending += b.getAmount();
                case DISPUTED -> disputed += b.getAmount();
            }
        }
        Map<VendorCategory, Double> byCategory = new EnumMap<>(VendorCategory.class);
        (season == null ? bills.spendByCategory(societyId) : bills.spendByCategoryForSeason(societyId, season.getId()))
                .forEach(row -> byCategory.put(row.getCategory(), row.getTotal()));

        double total = paid + pending + disputed;
        List<BudgetSummary.CategoryLine> lines = new ArrayList<>();
        for (VendorCategory c : VendorCategory.values()) {
            double amount = byCategory.getOrDefault(c, 0.0);
            lines.add(new BudgetSummary.CategoryLine(c, c.getLabel(), amount, total > 0 ? amount * 100.0 / total : 0.0));
        }
        double planned = season != null ? season.getPlannedBudget()
                : seasons.findBySociety_Id(societyId).stream().mapToDouble(Season::getPlannedBudget).sum();
        return new BudgetSummary(season == null ? null : season.getId(), season == null ? "All seasons" : season.getName(),
                planned, total, paid, pending, disputed, planned - total, lines);
    }

    // ------------------------------------------------------------------ internals

    private VendorBill find(Long societyId, Long billId) {
        return bills.findByIdAndSociety_Id(billId, societyId).orElseThrow(() -> new NotFoundException("Bill not found"));
    }

    private void apply(VendorBill b, Long societyId, BillRequest r) {
        if (r.amount() == null || r.amount() <= 0 || r.amount() > MAX_AMOUNT) {
            throw new BusinessRuleException("The amount must be greater than zero");
        }
        VendorContact vendor = vendors.findByIdAndSociety_Id(r.vendorId(), societyId)
                .orElseThrow(() -> new NotFoundException("Vendor not found"));
        b.setVendor(vendor);
        b.setSeason(r.seasonId() == null ? null : seasons.findByIdAndSociety_Id(r.seasonId(), societyId)
                .orElseThrow(() -> new NotFoundException("Season not found")));
        b.setTitle(r.title().trim());
        b.setAmount(r.amount());
        b.setBillDate(r.billDate() == null ? LocalDate.now() : r.billDate());
        b.setDescription(Text.clean(r.description()));
        b.setAttachmentUrl(Urls.requireWebUrl(r.attachmentUrl(), "Attachment link"));
    }
}
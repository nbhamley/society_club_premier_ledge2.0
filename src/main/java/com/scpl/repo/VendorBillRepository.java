package com.scpl.repo;

import com.scpl.model.BillStatus;
import com.scpl.model.VendorBill;
import com.scpl.model.VendorCategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VendorBillRepository extends SocietyScopedRepository<VendorBill, Long> {

    /** Row for the budget dashboard chart: spend grouped by vendor category. */
    interface CategorySpend {
        VendorCategory getCategory();

        Double getTotal();
    }

    /** Row for the vendor list's "past-bill sum" column. */
    interface VendorSpend {
        Long getVendorId();

        Double getTotal();
    }

    @EntityGraph(attributePaths = {"vendor", "season"})
    List<VendorBill> findBySociety_IdOrderByBillDateDesc(Long societyId);

    @EntityGraph(attributePaths = {"vendor", "season"})
    List<VendorBill> findBySociety_IdAndSeason_IdOrderByBillDateDesc(Long societyId, Long seasonId);

    List<VendorBill> findByVendor_Id(Long vendorId);

    long countBySociety_IdAndStatus(Long societyId, BillStatus status);

    // Separate "all seasons" and "one season" variants: a nullable season parameter in one query
    // fails type inference on PostgreSQL, so the null case is not handled in JPQL.

    @Query("select v.category as category, sum(b.amount) as total "
            + "from VendorBill b join b.vendor v "
            + "where b.society.id = :societyId "
            + "group by v.category")
    List<CategorySpend> spendByCategory(@Param("societyId") Long societyId);

    @Query("select v.category as category, sum(b.amount) as total "
            + "from VendorBill b join b.vendor v "
            + "where b.society.id = :societyId and b.season.id = :seasonId "
            + "group by v.category")
    List<CategorySpend> spendByCategoryForSeason(@Param("societyId") Long societyId,
                                                 @Param("seasonId") Long seasonId);

    @Query("select b.vendor.id as vendorId, sum(b.amount) as total "
            + "from VendorBill b where b.society.id = :societyId "
            + "group by b.vendor.id")
    List<VendorSpend> spendByVendor(@Param("societyId") Long societyId);

    @Query("select coalesce(sum(b.amount), 0) from VendorBill b "
            + "where b.society.id = :societyId and b.status = :status")
    Double totalByStatus(@Param("societyId") Long societyId, @Param("status") BillStatus status);

    @Query("select coalesce(sum(b.amount), 0) from VendorBill b "
            + "where b.society.id = :societyId and b.season.id = :seasonId")
    Double totalForSeason(@Param("societyId") Long societyId, @Param("seasonId") Long seasonId);
}
package com.scpl.repo;

import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;

import java.util.List;

public interface VendorContactRepository extends SocietyScopedRepository<VendorContact, Long> {

    List<VendorContact> findBySociety_IdOrderByNameAsc(Long societyId);

    List<VendorContact> findBySociety_IdAndCategoryOrderByNameAsc(Long societyId, VendorCategory category);

    List<VendorContact> findBySociety_IdAndActiveTrueOrderByNameAsc(Long societyId);

    /** Import conflict check for Vendors rows. */
    boolean existsBySociety_IdAndNameIgnoreCase(Long societyId, String name);
}
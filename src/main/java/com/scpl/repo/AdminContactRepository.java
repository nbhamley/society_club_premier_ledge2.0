package com.scpl.repo;

import com.scpl.model.AdminContact;

import java.util.List;

public interface AdminContactRepository extends SocietyScopedRepository<AdminContact, Long> {

    List<AdminContact> findBySociety_IdOrderByNameAsc(Long societyId);
}
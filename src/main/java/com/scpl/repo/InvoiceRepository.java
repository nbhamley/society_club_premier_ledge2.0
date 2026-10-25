package com.scpl.repo;

import com.scpl.model.Invoice;

import java.util.List;

public interface InvoiceRepository extends SocietyScopedRepository<Invoice, Long> {

    List<Invoice> findBySociety_IdOrderByIssuedAtDesc(Long societyId);

    List<Invoice> findBySubscription_IdOrderByIssuedAtDesc(Long subscriptionId);

    boolean existsByInvoiceNumber(String invoiceNumber);
}
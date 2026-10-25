package com.scpl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Demo gateway: approves every charge unless scpl.payment.stub.always-fail=true (to exercise the
 * dunning flow). Replace by registering a real PaymentService and deleting this class: two
 * beans of the same type would be ambiguous.
 */
@Service
public class StubPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentService.class);

    private final boolean alwaysFail;

    public StubPaymentService(@Value("${scpl.payment.stub.always-fail:false}") boolean alwaysFail) {
        this.alwaysFail = alwaysFail;
    }

    @Override
    public PaymentResult charge(Long societyId, String customerId, double amount, String currency, String description) {
        if (alwaysFail) {
            log.info("Stub payment DECLINED for society {}: {} {}", societyId, currency, amount);
            return PaymentResult.failed("Card declined (stub gateway)");
        }
        String reference = "stub_" + UUID.randomUUID();
        log.info("Stub payment OK for society {}: {} {} ({})", societyId, currency,
                String.format(Locale.ROOT, "%.2f", amount), reference);
        return PaymentResult.ok(reference);
    }
}
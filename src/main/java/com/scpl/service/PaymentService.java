package com.scpl.service;

/**
 * Payment gateway seam (§6). Takes plain values rather than entities so a Stripe implementation
 * can replace StubPaymentService without touching billing logic. Implementations must not throw
 * for an ordinary decline: they return a failed PaymentResult.
 */
public interface PaymentService {

    record PaymentResult(boolean success, String reference, String failureReason) {
        public static PaymentResult ok(String reference) {
            return new PaymentResult(true, reference, null);
        }

        public static PaymentResult failed(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }

    PaymentResult charge(Long societyId, String customerId, double amount, String currency, String description);
}
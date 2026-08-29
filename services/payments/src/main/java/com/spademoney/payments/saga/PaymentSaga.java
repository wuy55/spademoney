package com.spademoney.payments.saga;

import java.util.UUID;

/**
 * One payment in flight, as the driver sees it.
 *
 * Everything needed to decide what happens next is here, because everything
 * needed to decide what happens next was written down before anything happened.
 * That is the difference between a saga and a method with retries: this survives
 * the process.
 */
public record PaymentSaga(
        UUID id,
        String idempotencyKey,
        String requestFingerprint,
        String status,
        long payerAccountId,
        long payeeAccountId,
        long amountMinor,
        String currency,
        Long holdId,
        Long ledgerTransactionId,
        String failureCode,
        String failureMessage) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPENSATING = "COMPENSATING";
    public static final String COMPLETED = "COMPLETED";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String FAILED = "FAILED";

    public boolean isTerminal() {
        return COMPLETED.equals(status) || COMPENSATED.equals(status) || FAILED.equals(status);
    }
}

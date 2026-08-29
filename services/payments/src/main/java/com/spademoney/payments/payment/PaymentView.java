package com.spademoney.payments.payment;

import java.util.List;

/**
 * What a caller sees when it asks about a payment.
 *
 * <h2>Two status fields, on purpose</h2>
 * {@code status} is the answer to "did my payment work" and has three values a
 * client can branch on. {@code sagaStatus} is the internal state machine's own
 * word, exposed because it is the difference between "failed" and "failed and
 * we have already released your funds" — and because a payment stuck in
 * COMPENSATING is a thing an operator needs to be able to see without a database
 * session.
 *
 * Collapsing them would force a choice between lying to clients about internal
 * states or making every client learn a five-state machine. Publishing both
 * costs one field.
 */
public record PaymentView(
        String paymentId,
        String status,
        String sagaStatus,
        long payerAccountId,
        long payeeAccountId,
        long amountMinor,
        String currency,
        Long holdId,
        Long ledgerTransactionId,
        String failureCode,
        String failureMessage,
        List<StepView> steps) {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    /**
     * One step, including how many times it has been attempted and what went
     * wrong last time.
     *
     * Exposed rather than hidden because "it is on attempt 3 of 5, the Ledger is
     * timing out" is the single most useful thing anyone can be told about a
     * payment that has not finished. Hiding it turns every stuck payment into a
     * log-diving exercise.
     */
    public record StepView(String step, String kind, String status, int attempts, String lastError) {
    }
}

package com.spademoney.ledger.refund;

import com.spademoney.ledger.idempotency.IdempotentRequest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /refunds -- give back part or all of a transaction that already posted.
 *
 * Two identical partial refunds are legitimately different operations (refund
 * 10 twice = 20 back), and they share a fingerprint. That is correct: the
 * idempotency KEY is what distinguishes two intended refunds, while the
 * fingerprint only guards against one key being reused for different content.
 */
public record RefundRequest(
        @NotNull Long transactionId,
        @Positive long amountMinor) implements IdempotentRequest {

    @Override
    public String canonicalForm() {
        return "refund|%d|%d".formatted(transactionId, amountMinor);
    }
}

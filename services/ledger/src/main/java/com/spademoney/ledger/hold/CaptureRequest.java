package com.spademoney.ledger.hold;

import com.spademoney.ledger.idempotency.IdempotentRequest;

/**
 * POST /holds/{id}/capture -- the hold id comes from the path, the amount from
 * the body. Both land in the FINGERPRINT: the same key against a different hold
 * or a different amount is a 422, not a silently separate key scope.
 */
public record CaptureRequest(long holdId, long amountMinor) implements IdempotentRequest {

    @Override
    public String canonicalForm() {
        return "capture|%d|%d".formatted(holdId, amountMinor);
    }
}

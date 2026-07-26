package com.spademoney.ledger.hold;

import com.spademoney.ledger.idempotency.IdempotentRequest;

/**
 * POST /holds/{id}/void -- carries no body. Built by the controller from the
 * path variable so the hold id lands in the FINGERPRINT rather than in the
 * idempotency endpoint column: reusing one key against a different hold must be
 * a 422, not a silently separate key scope.
 */
public record VoidHoldRequest(long holdId) implements IdempotentRequest {

    @Override
    public String canonicalForm() {
        return "void|%d".formatted(holdId);
    }
}

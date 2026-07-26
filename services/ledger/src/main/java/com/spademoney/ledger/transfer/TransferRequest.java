package com.spademoney.ledger.transfer;

import com.spademoney.ledger.idempotency.IdempotentRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Wire-format intake for POST /transfers. Deliberately flat and "dumb": raw
 * fields, no invariants beyond presence/shape. The domain type (Money) is
 * constructed from these inside the service, so a malformed client payload
 * fails at our boundary rather than half-constructing a domain object.
 */
public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @Positive long amountMinor,
        @NotBlank String currency) implements IdempotentRequest {

    /**
     * Changing this string invalidates every idempotency row ever written for
     * this endpoint: stored fingerprints would stop matching, turning live
     * replays into 422 key-reuse errors. Do not reorder or reformat.
     */
    @Override
    public String canonicalForm() {
        return "%d|%d|%d|%s".formatted(fromAccountId, toAccountId, amountMinor, currency);
    }
}

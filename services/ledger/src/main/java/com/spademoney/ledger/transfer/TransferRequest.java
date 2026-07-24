package com.spademoney.ledger.transfer;

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
        @NotBlank String currency) {
}
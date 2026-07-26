package com.spademoney.ledger.hold;

import com.spademoney.ledger.idempotency.IdempotentRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /holds -- reserve funds on the payer for a named payee.
 *
 * expiresInSeconds is client-supplied because auth windows are domain-specific
 * (a fuel-pump pre-auth differs from a hotel incidental hold). It is bounded by
 * the service, not here, so the bound lives with the policy.
 */
public record AuthorizeRequest(
        @NotNull Long payerAccountId,
        @NotNull Long payeeAccountId,
        @Positive long amountMinor,
        @NotBlank String currency,
        @Positive long expiresInSeconds) implements IdempotentRequest {

    @Override
    public String canonicalForm() {
        return "authorize|%d|%d|%d|%s|%d".formatted(
                payerAccountId, payeeAccountId, amountMinor, currency, expiresInSeconds);
    }
}

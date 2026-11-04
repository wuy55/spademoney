package com.spademoney.payments.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Wire-format intake for POST /payments. Flat and dumb by design — presence and
 * shape only, no invariants.
 *
 * There is no Money type here, and that is deliberate. Payments carries
 * {@code amountMinor} as a {@code long} and {@code currency} as a
 * {@code String} and hands both to the Ledger, which owns every rule about
 * them. Copying a Money value object across the boundary would be duplicating
 * the invariant without duplicating the responsibility — the worst of both.
 *
 * The spending cap is the one place Payments adds amounts, and it never needed
 * Money either: it sums {@code amount_minor} in SQL and compares plain
 * {@code long}s, with the currency checked as a separate column. The earlier
 * plan to copy a trimmed Money in for it was dropped.
 */
public record PaymentRequest(
        @NotNull Long payerAccountId,
        @NotNull Long payeeAccountId,
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}

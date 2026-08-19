package com.spademoney.payments.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Wire-format intake for POST /payments. Flat and dumb by design — presence and
 * shape only, no invariants.
 *
 * There is no Money type here, and that is deliberate. Payments performs no
 * money arithmetic in this session: it carries {@code amountMinor} as a
 * {@code long} and {@code currency} as a {@code String} and hands both to the
 * Ledger, which owns every rule about them. Copying a Money value object across
 * the boundary before anything needs to add two amounts together would be
 * duplicating the invariant without duplicating the responsibility — the worst
 * of both. When Session 9's limit check has to sum amounts, a trimmed Money
 * gets copied in then, deliberately and with a reason.
 */
public record PaymentRequest(
        @NotNull Long payerAccountId,
        @NotNull Long payeeAccountId,
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}

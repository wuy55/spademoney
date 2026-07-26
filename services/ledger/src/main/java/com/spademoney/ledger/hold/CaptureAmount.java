package com.spademoney.ledger.hold;

import jakarta.validation.constraints.Positive;

/**
 * Wire body for capture. Separate from CaptureRequest so the hold id can only
 * come from the path: a client cannot send a body naming a different hold than
 * the URL it posted to.
 */
public record CaptureAmount(@Positive long amountMinor) {
}

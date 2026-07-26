package com.spademoney.ledger.refund;

/**
 * Wire-format result of a refund. Stored verbatim for replay.
 *
 * totalRefundedMinor and remainingRefundableMinor are both derived from the
 * entries of every REFUND transaction naming the original -- never stored on
 * the original row, which is immutable.
 */
public record RefundResponse(
        Long refundTransactionId,
        Long reversedTransactionId,
        long amountMinor,
        String currency,
        long totalRefundedMinor,
        long remainingRefundableMinor) {
}

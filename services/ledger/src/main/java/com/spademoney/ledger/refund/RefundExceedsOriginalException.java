package com.spademoney.ledger.refund;

/**
 * Refunds against one transaction may never sum past what it posted. Without
 * this cap a merchant could hand back money that was never taken, and the
 * "refund" would be an unbacked transfer wearing a refund's name.
 */
public class RefundExceedsOriginalException extends RuntimeException {
    public RefundExceedsOriginalException(long transactionId, long requestedMinor,
            long alreadyRefundedMinor, long originalMinor) {
        super("Refund of " + requestedMinor + " exceeds transaction " + transactionId
                + ": " + alreadyRefundedMinor + " of " + originalMinor + " is already refunded");
    }
}

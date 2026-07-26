package com.spademoney.ledger.refund;

/** The transaction a refund names does not exist. */
public class RefundTargetNotFoundException extends RuntimeException {
    public RefundTargetNotFoundException(long transactionId) {
        super("No transaction with id " + transactionId + " to refund");
    }
}

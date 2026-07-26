package com.spademoney.ledger.refund;

/**
 * The transaction exists but is not a refundable shape.
 *
 * A REFUND cannot itself be refunded: undoing an undo is a new payment, and
 * calling it a refund would let a chain of reversals drift arbitrarily far from
 * the money that actually moved. Anything that is not a simple two-entry
 * posting is also rejected, because this implementation reverses exactly one
 * debit and one credit -- a four-entry FX posting would need its own logic
 * rather than silently reversing the wrong pair.
 */
public class UnrefundableTransactionException extends RuntimeException {
    public UnrefundableTransactionException(long transactionId, String reason) {
        super("Transaction " + transactionId + " cannot be refunded: " + reason);
    }
}

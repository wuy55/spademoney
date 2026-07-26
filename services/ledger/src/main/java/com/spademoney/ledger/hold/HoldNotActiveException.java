package com.spademoney.ledger.hold;

/**
 * The hold exists but is already captured, voided or expired. Terminal states
 * are final (holds_terminal_is_final trigger), so this is never retryable.
 * Maps to 422.
 */
public class HoldNotActiveException extends RuntimeException {
    public HoldNotActiveException(long holdId, String status) {
        super("Hold " + holdId + " is " + status + "; only ACTIVE holds can be resolved");
    }
}

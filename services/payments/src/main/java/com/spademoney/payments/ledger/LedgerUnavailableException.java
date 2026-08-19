package com.spademoney.payments.ledger;

/**
 * The Ledger could not be reached, or answered 5xx.
 *
 * Distinct from {@link LedgerTimeoutException} on purpose: a refused connection
 * says the request was never processed, so a retry is safe. A read timeout says
 * nothing of the kind.
 */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message) {
        super(message);
    }

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

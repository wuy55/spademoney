package com.spademoney.payments.ledger;

import org.springframework.http.HttpStatusCode;

/**
 * The Ledger answered, and the answer was "no".
 *
 * A 4xx from the Ledger is *information*, not a failure of the call: the money
 * definitively did not move, and the reason is in the code. Payments passes
 * both through rather than flattening them to a generic 502, because
 * INSUFFICIENT_FUNDS and "the ledger is down" call for completely different
 * client behaviour.
 */
public class LedgerRejectedException extends RuntimeException {

    private final HttpStatusCode status;
    private final LedgerError error;

    public LedgerRejectedException(HttpStatusCode status, LedgerError error) {
        super("Ledger rejected the transfer: %s %s".formatted(status, error.code()));
        this.status = status;
        this.error = error;
    }

    public HttpStatusCode status() {
        return status;
    }

    public LedgerError error() {
        return error;
    }
}

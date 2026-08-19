package com.spademoney.payments.ledger;

/**
 * The read timeout fired: the request reached the Ledger, and no answer came
 * back in time.
 *
 * <h2>This is the honest ambiguity, and Session 6 deliberately leaves it</h2>
 * Payments does not know whether the money moved. The transfer may have
 * committed and the response been lost; it may have been rolled back; it may
 * still be in flight and commit a moment from now. Every one of those is
 * consistent with what Payments observed, which is nothing.
 *
 * The temptation is to paper over it — retry, or call GET /transfers to look.
 * Both are wrong here. A blind retry can double-charge, because the derived
 * idempotency key is not yet deterministic across retries
 * ({@link LedgerIdempotencyKeys}). A status lookup is a guess dressed as a
 * check: it races the in-flight commit and reports "no transfer" for a transfer
 * that is about to exist.
 *
 * So this surfaces as 504 and stops there. Sessions 7-8 (outbox and inbox) make
 * the operation recoverable, and Session 9's deterministic key makes the retry
 * safe. Fixing it before those exist would mean building the wrong fix.
 */
public class LedgerTimeoutException extends RuntimeException {

    public LedgerTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

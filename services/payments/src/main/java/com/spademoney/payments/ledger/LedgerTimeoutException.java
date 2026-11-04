package com.spademoney.payments.ledger;

/**
 * The read timeout fired: the request reached the Ledger, and no answer came
 * back in time.
 *
 * <h2>The outcome is unknown, and that is safe to retry</h2>
 * Payments does not know whether the call took effect. It may have committed
 * and the response been lost; it may have been rolled back; it may still be in
 * flight and commit a moment from now. Every one of those is consistent with
 * what Payments observed, which is nothing.
 *
 * Inside the saga that ambiguity is left unresolved and made harmless instead.
 * Every step sends a key derived from the saga and the step
 * ({@link LedgerIdempotencyKeys#forStep}), so a retry carries the key the first
 * attempt carried, and if that attempt did land the Ledger replays its stored
 * answer. {@code LedgerCallStep} therefore retries this exception like any
 * other transport failure. A status lookup is still the wrong tool: it races
 * the in-flight commit and reports "no transfer" for one about to exist.
 *
 * At the HTTP edge this maps to 504, but no current endpoint calls the Ledger
 * synchronously, so no caller receives one today.
 *
 * <h2>History</h2>
 * When this class was written the key was minted fresh per request, so a retry
 * could double-charge, and the synchronous POST /payments surfaced a 504 and
 * stopped. The fix was deliberately deferred until the outbox, inbox and a
 * deterministic key existed. That is where "make the question harmless rather
 * than answer it" came from.
 */
public class LedgerTimeoutException extends RuntimeException {

    public LedgerTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

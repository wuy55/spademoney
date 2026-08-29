package com.spademoney.payments.web;

/**
 * The caller replayed an Idempotency-Key with a different payment.
 *
 * Reported (422) rather than absorbed. Treating it as a replay would return the
 * FIRST payment's status for a request describing a different one, so a client
 * that mixed up two retries would be told its second payment succeeded when the
 * one that actually ran was the first. Same rule, same status code, as the
 * Ledger's contract.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}

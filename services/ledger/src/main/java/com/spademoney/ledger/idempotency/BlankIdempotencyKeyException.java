package com.spademoney.ledger.idempotency;

/**
 * The header was present but blank. Distinct from a MISSING header (which
 * Spring already turns into 400 automatically) so both client mistakes land
 * on the same status code instead of one being 400 and the other 422.
 */
public class BlankIdempotencyKeyException extends RuntimeException {
    public BlankIdempotencyKeyException(String message) {
        super(message);
    }
}

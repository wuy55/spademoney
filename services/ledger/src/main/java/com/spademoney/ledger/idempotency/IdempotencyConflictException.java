package com.spademoney.ledger.idempotency;

/**
 * A request with this key is already in flight. Maps to 409 + Retry-After.
 * The client should retry; once the original completes, the retry resolves
 * into a replay of the stored response.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final int retryAfterSeconds;

    public IdempotencyConflictException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
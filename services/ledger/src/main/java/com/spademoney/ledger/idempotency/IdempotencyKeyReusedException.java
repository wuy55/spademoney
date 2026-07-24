package com.spademoney.ledger.idempotency;

/**
 * The key was already used for a different request (fingerprint mismatch).
 * A key names one logical request. Maps to 422.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
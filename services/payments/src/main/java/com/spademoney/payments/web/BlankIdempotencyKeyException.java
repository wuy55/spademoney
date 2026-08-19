package com.spademoney.payments.web;

/** Idempotency-Key header present but empty or whitespace. */
public class BlankIdempotencyKeyException extends RuntimeException {

    public BlankIdempotencyKeyException() {
        super("Idempotency-Key header must not be blank");
    }
}

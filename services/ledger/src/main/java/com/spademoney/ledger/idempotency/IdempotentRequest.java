package com.spademoney.ledger.idempotency;

/**
 * Every money-mutating request body, hashed into an idempotency fingerprint.
 *
 * canonicalForm() is hand-written per type rather than derived by serializing
 * the record. Jackson orders record components by declaration order, so
 * reordering a record's fields would silently change every fingerprint it has
 * ever produced -- turning live replays into 422 key-reuse errors. An explicit
 * string cannot drift without someone editing this method.
 *
 * NOT sealed, deliberately. A sealed type may only permit subtypes in its own
 * package unless the whole application is one named module, and the wire DTOs
 * live with their endpoints (transfer/, hold/) rather than here. Sealing would
 * mean dragging every endpoint's HTTP contract into the idempotency package to
 * buy a compile-time exhaustiveness check we never pattern-match on. The
 * abstract method is what actually forces the decision: a new request type
 * cannot reach IdempotencyService.execute without implementing canonicalForm().
 */
public interface IdempotentRequest {

    /** Every semantic field of the request, in a fixed order, pipe-delimited. */
    String canonicalForm();
}

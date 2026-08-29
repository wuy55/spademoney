package com.spademoney.payments.saga;

/** One persisted step: its key, the body it will always send, and how it went. */
public record SagaStepRow(
        String step,
        String kind,
        String status,
        String idempotencyKey,
        String command,
        String result,
        int attempts,
        String lastError) {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
}

package com.spademoney.payments.inbox;

/**
 * The message is malformed in a way no amount of retrying will fix -- a missing
 * identity header, an id that is not a UUID.
 *
 * Distinguished from an ordinary failure because the response is opposite. A
 * database blip should be retried; a message with no event id should not,
 * because every retry blocks the partition behind a record that cannot ever
 * succeed. One poison message would otherwise stop every event for every
 * aggregate on that partition, which is a far bigger outage than the one bad
 * record deserves.
 *
 * Registered as non-retryable, so it is dead-lettered on the first attempt.
 */
public class UnprocessableEventException extends RuntimeException {

    public UnprocessableEventException(String message) {
        super(message);
    }

    public UnprocessableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

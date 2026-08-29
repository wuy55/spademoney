package com.spademoney.payments.inbox;

import java.util.UUID;

/**
 * One Ledger event as it arrived, before anything has been done about it.
 *
 * The payload stays a String. Payments deliberately does not import the
 * Ledger's event classes (ADR-007: no shared module), and it does not need to:
 * each handler parses only the fields it actually uses. That is not laziness,
 * it is what keeps the Ledger free to add a field without a coordinated
 * release -- a consumer that deserializes into a mirrored class breaks on the
 * first unknown property, which is the coupling the split was supposed to
 * remove.
 */
public record InboxEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload,
        String topic,
        int partition,
        long offset) {
}

package com.spademoney.ledger.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One committed, not-yet-published outbox row, as the relay sees it.
 *
 * {@code payload} stays a String all the way to the broker. It was serialized
 * once, inside the transaction that made it true, and re-parsing it here only to
 * re-serialize it would create an opportunity for the published bytes to differ
 * from the recorded ones. The relay is a courier, not an author.
 */
public record OutboxRecord(
        long id,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OffsetDateTime occurredAt) {

    /**
     * The broker partition key. Type-qualified because hold 7 and transaction 7
     * are different aggregates: keying on the bare id would put their events on
     * the same partition, which is harmless, and -- far worse -- would let a
     * future consumer that trusts the key confuse the two.
     */
    public String partitionKey() {
        return aggregateType + ":" + aggregateId;
    }
}

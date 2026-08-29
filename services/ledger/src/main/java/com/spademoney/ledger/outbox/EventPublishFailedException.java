package com.spademoney.ledger.outbox;

/**
 * The broker did not acknowledge this event.
 *
 * Not an error condition for the ledger: the money already committed and the
 * event is still safely in the outbox. It only means delivery is late. The relay
 * catches this, records it against the row and stops the batch, and the next
 * tick tries the same event again -- which is the whole reason the outbox is a
 * table rather than a method call.
 */
public class EventPublishFailedException extends RuntimeException {

    private final transient OutboxRecord record;

    EventPublishFailedException(OutboxRecord record, Throwable cause) {
        super("Broker did not acknowledge event " + record.eventId()
                + " (" + record.eventType() + ") within the send timeout", cause);
        this.record = record;
    }

    public OutboxRecord record() {
        return record;
    }
}

package com.spademoney.payments.inbox;

/**
 * Something that reacts to a Ledger event.
 *
 * <h2>Handlers run inside the inbox's transaction</h2>
 * {@link InboxService} opens one local transaction, records the event, and
 * calls every interested handler inside it. A handler must therefore not open
 * its own transaction and must not do anything it cannot undo by throwing --
 * no HTTP calls, no publishing. If a handler throws, the dedupe row rolls back
 * with the effect, so the event is genuinely unprocessed and redelivery is
 * correct rather than merely tolerable.
 *
 * The list is empty in this commit. The first handler is the saga's step
 * confirmation, and it is the reason the seam exists: a saga must learn that a
 * hold was authorized even when the HTTP response that would have told it was
 * lost.
 */
public interface InboxEventHandler {

    /** @return true if this handler wants the event. */
    boolean handles(String eventType);

    /**
     * Apply the event. Runs in the inbox transaction; throwing rolls back the
     * dedupe record too, which is what makes redelivery the correct response.
     */
    void handle(InboxEvent event);
}

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
 * One implementation exists: {@code saga/SagaLedgerEventHandler}, which
 * handles {@code HoldExpired}. It is the reason the seam exists: an expiry is
 * a fact nobody asked for, so no HTTP reply will ever carry it. The saga
 * learns about authorizations from the replies to its own commands, and
 * retries when one is lost.
 *
 * History: the seam was added before any handler, and the first one was
 * planned as step confirmation, learning about an authorization when its HTTP
 * reply was lost. What shipped in 822cb01 handles expiry instead; a lost reply
 * is covered by retrying with the deterministic step key.
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

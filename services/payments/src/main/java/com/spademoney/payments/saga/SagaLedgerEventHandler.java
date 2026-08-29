package com.spademoney.payments.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.spademoney.payments.inbox.InboxEvent;
import com.spademoney.payments.inbox.InboxEventHandler;

/**
 * The event stream's one job in the saga: telling Payments about things that
 * happened to a hold <em>without</em> Payments asking.
 *
 * <h2>Why this cannot be done with a retry</h2>
 * Every other fact the saga needs comes back on the HTTP response of a command
 * it issued, and if that response is lost it retries. An expiry is different:
 * nobody issued it. The Ledger releases a lapsed hold on its own, and no reply
 * to any request Payments made will ever mention it. Without the event, a saga
 * whose hold expired would keep retrying CAPTURE against funds that had already
 * been released, exhaust its attempts, and report a timeout — the right ending
 * for the wrong reason, several minutes late.
 *
 * That is the honest answer to "what does the event stream actually buy you
 * here". Not the happy path — commands and their replies handle that — but the
 * facts that originate on the other side of the boundary.
 *
 * <h2>Turning around, not failing</h2>
 * The saga moves to COMPENSATING rather than straight to FAILED, because the
 * local limit may already be consumed and must be released. The VOID
 * compensation then discovers the hold is already EXPIRED and treats its goal as
 * met — see {@link VoidStep}. Two independent mechanisms that happen to compose
 * correctly, which is what you want from a state machine and its inputs.
 *
 * The transition is conditional on the saga still being RUNNING, so an expiry
 * arriving at the same instant as a step failure cannot restart a turnaround
 * that is already under way.
 */
@Component
class SagaLedgerEventHandler implements InboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SagaLedgerEventHandler.class);

    private static final String HOLD_EXPIRED = "HoldExpired";

    private final SagaRepository sagas;
    private final ObjectMapper objectMapper;

    SagaLedgerEventHandler(SagaRepository sagas, ObjectMapper objectMapper) {
        this.sagas = sagas;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean handles(String eventType) {
        return HOLD_EXPIRED.equals(eventType);
    }

    @Override
    public void handle(InboxEvent event) {
        long holdId = objectMapper.readTree(event.payload()).get("holdId").asLong();

        sagas.findByHoldId(holdId).ifPresent(saga -> {
            if (!PaymentSaga.RUNNING.equals(saga.status())) {
                return;
            }
            boolean turned = sagas.startCompensating(saga.id(), "HOLD_EXPIRED",
                    "Hold " + holdId + " expired before the payment could capture it");
            if (turned) {
                log.warn("Saga {} is compensating: its hold {} expired", saga.id(), holdId);
            }
        });
    }
}

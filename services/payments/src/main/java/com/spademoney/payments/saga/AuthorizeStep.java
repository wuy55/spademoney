package com.spademoney.payments.saga;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.spademoney.payments.ledger.LedgerClient;
import com.spademoney.payments.ledger.LedgerCommands;

/**
 * Step 1: reserve the payer's funds in the Ledger.
 *
 * Authorizing rather than transferring outright is what gives the saga a middle.
 * A hold is a reservation the payer cannot spend twice and that the Ledger will
 * release on its own if nobody claims it, so the window between "we have the
 * money" and "we have taken the money" is safe to leave open for as long as the
 * rest of the saga needs — and safe to abandon if it never finishes.
 */
@Component
class AuthorizeStep extends LedgerCallStep {

    private final LedgerClient ledger;
    private final SagaRepository sagas;
    private final SagaProperties properties;
    private final ObjectMapper objectMapper;

    AuthorizeStep(LedgerClient ledger, SagaRepository sagas, SagaProperties properties,
            ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.sagas = sagas;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String step() {
        return SagaPlan.AUTHORIZE;
    }

    @Override
    public Object buildCommand(PaymentSaga saga) {
        return new LedgerCommands.Authorize(
                saga.payerAccountId(), saga.payeeAccountId(),
                saga.amountMinor(), saga.currency(),
                properties.holdExpiry().toSeconds());
    }

    @Override
    public StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command) {
        LedgerCommands.Authorize body = objectMapper.readValue(command, LedgerCommands.Authorize.class);

        StepOutcome outcome = call(() ->
                ledger.post("/holds", body, idempotencyKey, LedgerCommands.HoldResult.class));

        // Record the hold id before returning, so that even if this process dies
        // between here and the driver marking the step succeeded, the saga knows
        // which hold it owns. The retry that follows is a replay -- same key,
        // same body -- and simply re-learns the same id.
        if (outcome instanceof StepOutcome.Succeeded success
                && success.result() instanceof LedgerCommands.HoldResult hold
                && hold.holdId() != null) {
            sagas.recordHold(saga.id(), hold.holdId());
        }
        return outcome;
    }
}

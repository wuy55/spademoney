package com.spademoney.payments.saga;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.spademoney.payments.ledger.LedgerClient;
import com.spademoney.payments.ledger.LedgerCommands;

/**
 * Step 3: settle the hold. This is where money actually moves.
 *
 * It is also the saga's point of no return, and the reason {@link SagaPlan} has
 * no compensator for it: undoing a capture means posting a refund, which is a
 * decision about a customer's money that a retry loop has no business making on
 * its own. Everything before this step is undoable; this step either succeeds or
 * the saga unwinds without it.
 */
@Component
class CaptureStep extends LedgerCallStep {

    private final LedgerClient ledger;
    private final SagaRepository sagas;
    private final ObjectMapper objectMapper;

    CaptureStep(LedgerClient ledger, SagaRepository sagas, ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.sagas = sagas;
        this.objectMapper = objectMapper;
    }

    @Override
    public String step() {
        return SagaPlan.CAPTURE;
    }

    @Override
    public Object buildCommand(PaymentSaga saga) {
        return new LedgerCommands.Capture(saga.amountMinor());
    }

    @Override
    public StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command) {
        if (saga.holdId() == null) {
            // Only reachable if the plan were changed to run capture before
            // authorize. Loud rather than a null pointer three frames down.
            return new StepOutcome.Terminal("SAGA_INVARIANT",
                    "Capture reached with no hold recorded on the saga");
        }
        LedgerCommands.Capture body = objectMapper.readValue(command, LedgerCommands.Capture.class);

        StepOutcome outcome = call(() -> ledger.post(
                "/holds/" + saga.holdId() + "/capture", body, idempotencyKey,
                LedgerCommands.CaptureResult.class));

        if (outcome instanceof StepOutcome.Succeeded success
                && success.result() instanceof LedgerCommands.CaptureResult captured
                && captured.transactionId() != null) {
            sagas.recordTransaction(saga.id(), captured.transactionId());
        }
        return outcome;
    }
}

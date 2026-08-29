package com.spademoney.payments.saga;

import org.springframework.stereotype.Component;

import com.spademoney.payments.ledger.LedgerClient;
import com.spademoney.payments.ledger.LedgerCommands;

/**
 * Compensation for {@link AuthorizeStep}: release the hold.
 *
 * <h2>This is a new operation, not an undo</h2>
 * Nothing is erased. The Ledger records that a hold was authorized and then
 * voided, and emits an event for each. That is the honest shape of compensation
 * across a service boundary: you cannot roll back another service's committed
 * transaction, you can only ask it to do the opposite thing, and the history
 * keeps both.
 *
 * <h2>A compensation is defined by its goal, not by its action</h2>
 * The goal is "the payer's funds are no longer reserved". Voiding is one way to
 * reach it; the hold lapsing on its own is another, and the Ledger releases
 * expired holds without being asked. So when the Ledger answers
 * HOLD_NOT_ACTIVE, the right question is not "did my void work" but "is the
 * money still held".
 *
 * This step therefore reads the hold back and decides:
 *
 * <ul>
 *   <li>VOIDED or EXPIRED — the goal is met, by whatever route. Succeeded.</li>
 *   <li>CAPTURED — the goal is NOT met and never will be: the money moved.
 *       Terminal, and the driver escalates it as a compensation that failed,
 *       because a saga quietly reporting COMPENSATED over a real charge is
 *       exactly the lie this whole milestone exists to make impossible.</li>
 * </ul>
 *
 * Treating every HOLD_NOT_ACTIVE as success would be one line shorter and would
 * swallow that second case.
 */
@Component
class VoidStep extends LedgerCallStep {

    private static final String HOLD_NOT_ACTIVE = "HOLD_NOT_ACTIVE";

    private final LedgerClient ledger;

    VoidStep(LedgerClient ledger) {
        this.ledger = ledger;
    }

    @Override
    public String step() {
        return SagaPlan.VOID;
    }

    /**
     * The Ledger's void endpoint takes no body — it derives its idempotency
     * fingerprint from the hold in the path. An empty object is persisted anyway
     * so every step row has a command and the "always resend what was stored"
     * rule has no exceptions to remember.
     */
    @Override
    public Object buildCommand(PaymentSaga saga) {
        return java.util.Map.of("holdId", saga.holdId() == null ? -1L : saga.holdId());
    }

    @Override
    public StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command) {
        if (saga.holdId() == null) {
            return new StepOutcome.Terminal("SAGA_INVARIANT", "Void reached with no hold to release");
        }
        StepOutcome outcome = call(() -> ledger.post(
                "/holds/" + saga.holdId() + "/void", null, idempotencyKey,
                LedgerCommands.HoldResult.class));

        if (outcome instanceof StepOutcome.Terminal terminal
                && HOLD_NOT_ACTIVE.equals(terminal.code())) {
            return reconcileAgainstTheHold(saga, terminal);
        }
        return outcome;
    }

    /**
     * The Ledger says the hold is not ACTIVE. Find out what it is instead.
     *
     * The status is read back rather than parsed out of the error message, which
     * would be a contract nobody agreed to and one rewording away from breaking.
     * If that read itself fails, the outcome stays retryable: not knowing is a
     * reason to ask again, not a reason to conclude anything.
     */
    private StepOutcome reconcileAgainstTheHold(PaymentSaga saga, StepOutcome.Terminal terminal) {
        LedgerCommands.HoldResult hold;
        try {
            hold = ledger.get("/holds/" + saga.holdId(), LedgerCommands.HoldResult.class);
        } catch (RuntimeException e) {
            return new StepOutcome.Retry(
                    "hold is not active but its state could not be read: " + e.getMessage());
        }

        return switch (hold.status()) {
            case "VOIDED", "EXPIRED" -> new StepOutcome.Succeeded(hold);
            case "CAPTURED" -> new StepOutcome.Terminal("HOLD_ALREADY_CAPTURED",
                    "Hold " + saga.holdId() + " was captured; the funds moved and cannot be released here");
            default -> terminal;
        };
    }
}

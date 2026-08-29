package com.spademoney.payments.saga;

import java.util.function.Supplier;

import com.spademoney.payments.ledger.LedgerRejectedException;
import com.spademoney.payments.ledger.LedgerTimeoutException;
import com.spademoney.payments.ledger.LedgerUnavailableException;

/**
 * Shared behaviour for the steps that talk to the Ledger: turning the client's
 * four outcomes into the driver's three.
 *
 * <pre>
 *   2xx                         -> Succeeded
 *   4xx  (LedgerRejected)       -> Terminal   it will never succeed
 *   5xx / unreachable           -> Retry      it was not processed
 *   read timeout                -> Retry      it MIGHT have been processed
 * </pre>
 *
 * <h2>The last line is the whole milestone</h2>
 * In session 6 a read timeout was a dead end. Payments could not know whether
 * the transfer had posted, could not retry without risking a double charge, and
 * could not ask without racing an in-flight commit — so it returned 504 and
 * stopped.
 *
 * Nothing about that ambiguity has been resolved. What changed is that it
 * stopped mattering. The step's idempotency key is derived from a persisted saga
 * id and is identical on every attempt, and the body is the persisted one, so
 * resending is a <em>replay</em> at the Ledger: if the first attempt landed, the
 * Ledger returns its own stored answer and no second effect occurs. The correct
 * response to "I don't know" turns out not to be finding out — it is making the
 * question harmless.
 *
 * That is also why 502 and 504 land in the same bucket <em>here</em> while
 * remaining different answers at the API boundary. The saga treats them alike
 * because its retry is safe either way; a caller still needs to be told which
 * one happened.
 */
abstract class LedgerCallStep implements SagaStepExecutor {

    /**
     * @param call the HTTP call, already carrying the persisted key and body
     */
    protected StepOutcome call(Supplier<Object> call) {
        try {
            return new StepOutcome.Succeeded(call.get());
        } catch (LedgerRejectedException e) {
            // The Ledger considered it and said no. Insufficient funds, a hold
            // already resolved, a currency mismatch: nothing about waiting
            // changes any of them.
            return new StepOutcome.Terminal(e.error().code(), e.error().message());
        } catch (LedgerUnavailableException e) {
            return new StepOutcome.Retry("ledger unavailable: " + e.getMessage());
        } catch (LedgerTimeoutException e) {
            return new StepOutcome.Retry("ledger timed out (outcome unknown, retry is a replay): "
                    + e.getMessage());
        }
    }
}

package com.spademoney.payments.saga;

/**
 * One step of the saga.
 *
 * <h2>The command is built once and then never rebuilt</h2>
 * {@link #buildCommand} runs the first time a step is reached; its output is
 * persisted on the step row, and {@link #execute} is handed that persisted copy
 * on every attempt, including the first. Executors therefore have no way to
 * recompute a body from saga state that has since moved on — which is the
 * failure that would wedge a saga permanently against the Ledger's
 * fingerprint check rather than failing it loudly.
 *
 * <h2>Executors do not decide the saga's fate</h2>
 * They report a {@link StepOutcome} and nothing else: no writes to the saga row,
 * no scheduling, no compensation. Keeping that decision in one place
 * ({@code SagaDriver}) is what makes the state machine reviewable — otherwise
 * "what happens after a failed capture" is answered in four files.
 */
public interface SagaStepExecutor {

    /** The step name from {@link SagaPlan}. */
    String step();

    /**
     * Build the request body for this step. Called once per saga, before the
     * first attempt, and the result is persisted.
     */
    Object buildCommand(PaymentSaga saga);

    /**
     * @param saga           the saga as it stands now
     * @param idempotencyKey {@code saga:{id}:{step}}, identical on every attempt
     * @param command        the body as persisted, deserialized back into this
     *                       step's own command type
     */
    StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command);
}

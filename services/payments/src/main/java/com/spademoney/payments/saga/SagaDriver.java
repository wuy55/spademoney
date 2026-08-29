package com.spademoney.payments.saga;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * The orchestrator: the one place that decides what a payment does next.
 *
 * <h2>Everything is a poll</h2>
 * There is no synchronous "start the saga" path. {@code POST /payments} writes
 * the saga and returns 202; this driver picks it up on its next tick and keeps
 * picking it up until it reaches a terminal state. That costs a little latency
 * and buys something worth much more: <em>recovery is not a separate code
 * path</em>. A saga resumed after a crash is driven by the same method, in the
 * same order, as one that has never failed — so the recovery logic is exercised
 * by every test in the suite rather than by the one test that remembers to kill
 * something. Systems where "resume" is its own routine are systems where resume
 * is the least-tested code in the building.
 *
 * <h2>The state machine</h2>
 * <pre>
 *   RUNNING       --- all forward steps succeeded ------------> COMPLETED
 *                 --- a step is terminal, something to undo --> COMPENSATING
 *                 --- a step is terminal, nothing to undo ----> FAILED
 *   COMPENSATING  --- all compensations succeeded ------------> COMPENSATED
 *                 --- a compensation cannot be completed -----> FAILED (needs a human)
 * </pre>
 *
 * <h2>Why exhausting retries is a terminal failure and not a stuck saga</h2>
 * A step that keeps timing out is retried a bounded number of times and then
 * treated as terminal. Retrying forever leaves a payment that never resolves --
 * no charge, no decline, no answer — which is the worst outcome for everyone
 * involved. Giving up compensates, releases the payer's funds, and produces a
 * decline the customer can act on.
 *
 * <h2>The one honest dead end</h2>
 * If a COMPENSATION exhausts its retries, the saga is marked FAILED with
 * COMPENSATION_FAILED. That is a genuine "this needs a person": funds are held
 * in the Ledger that Payments could not release. It is not swept under a retry
 * loop, and the reconciliation job reports it. A system that cannot admit this
 * state simply hides it.
 */
@Component
public class SagaDriver {

    private static final Logger log = LoggerFactory.getLogger(SagaDriver.class);

    static final String CODE_STEP_EXHAUSTED = "STEP_RETRIES_EXHAUSTED";
    static final String CODE_COMPENSATION_FAILED = "COMPENSATION_FAILED";

    private final SagaRepository sagas;
    private final Map<String, SagaStepExecutor> executors;
    private final SagaProperties properties;
    private final ObjectMapper objectMapper;

    public SagaDriver(SagaRepository sagas, List<SagaStepExecutor> executors,
            SagaProperties properties, ObjectMapper objectMapper) {
        this.sagas = sagas;
        this.executors = executors.stream()
                .collect(Collectors.toMap(SagaStepExecutor::step, Function.identity()));
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return how many sagas this tick touched. */
    public int runOnce() {
        List<PaymentSaga> claimed = sagas.claimDue(properties.batchSize(), properties.lease());
        for (PaymentSaga saga : claimed) {
            try {
                advance(saga);
            } catch (RuntimeException e) {
                // One saga's unexpected failure must not stop the others in this
                // tick. The lease it holds expires on its own, so it is retried
                // rather than abandoned.
                log.error("Saga {} threw while advancing; it will be retried when its lease expires",
                        saga.id(), e);
            }
        }
        return claimed.size();
    }

    void advance(PaymentSaga saga) {
        List<SagaStepRow> steps = sagas.findSteps(saga.id());

        if (PaymentSaga.RUNNING.equals(saga.status())) {
            advanceForward(saga, steps);
        } else if (PaymentSaga.COMPENSATING.equals(saga.status())) {
            advanceCompensation(saga, steps);
        }
    }

    private void advanceForward(PaymentSaga saga, List<SagaStepRow> steps) {
        String next = SagaPlan.FORWARD_STEPS.stream()
                .filter(step -> !succeeded(steps, step))
                .findFirst()
                .orElse(null);

        if (next == null) {
            sagas.complete(saga.id());
            log.info("Saga {} completed (ledger transaction {})", saga.id(), saga.ledgerTransactionId());
            return;
        }
        runStep(saga, next);
    }

    private void advanceCompensation(PaymentSaga saga, List<SagaStepRow> steps) {
        List<String> succeededForward = steps.stream()
                .filter(step -> SagaPlan.FORWARD.equals(step.kind()))
                .filter(step -> SagaStepRow.SUCCEEDED.equals(step.status()))
                .map(SagaStepRow::step)
                .toList();

        String next = SagaPlan.compensationsFor(succeededForward).stream()
                .filter(step -> !succeeded(steps, step))
                .findFirst()
                .orElse(null);

        if (next == null) {
            sagas.markCompensated(saga.id());
            log.info("Saga {} compensated after {}", saga.id(), saga.failureCode());
            return;
        }
        runStep(saga, next);
    }

    private void runStep(PaymentSaga saga, String step) {
        SagaStepExecutor executor = executors.get(step);
        if (executor == null) {
            throw new IllegalStateException("No executor registered for saga step " + step);
        }

        SagaStepRow row = ensureStep(saga, executor);
        StepOutcome outcome = executor.execute(saga, row.idempotencyKey(), row.command());

        switch (outcome) {
            case StepOutcome.Succeeded success -> {
                sagas.markStepSucceeded(saga.id(), step, serialize(success.result()));
                // Continue on the next tick rather than recursing: one tick, one
                // step, so a saga can never monopolise the driver thread and a
                // bug in the plan cannot become an infinite loop inside a tick.
                sagas.scheduleNow(saga.id());
            }
            case StepOutcome.Retry retry -> onRetry(saga, step, retry);
            case StepOutcome.Terminal terminal -> onTerminal(saga, step, terminal.code(), terminal.message());
        }
    }

    private void onRetry(PaymentSaga saga, String step, StepOutcome.Retry retry) {
        int attempts = sagas.recordAttempt(saga.id(), step, retry.reason());

        if (attempts >= properties.maxAttempts()) {
            log.warn("Saga {} step {} exhausted {} attempts: {}",
                    saga.id(), step, attempts, retry.reason());
            onTerminal(saga, step, CODE_STEP_EXHAUSTED,
                    step + " did not succeed after " + attempts + " attempts: " + retry.reason());
            return;
        }

        Duration delay = SagaBackoff.delayFor(attempts, properties);
        sagas.scheduleAt(saga.id(), OffsetDateTime.now().plus(delay));
        log.info("Saga {} step {} attempt {} will retry in {}ms: {}",
                saga.id(), step, attempts, delay.toMillis(), retry.reason());
    }

    private void onTerminal(PaymentSaga saga, String step, String code, String message) {
        sagas.markStepFailed(saga.id(), step, code + ": " + message);

        if (SagaPlan.COMPENSATION.equals(SagaPlan.kindOf(step))) {
            // A compensation that cannot complete is the one state this system
            // cannot resolve on its own. Say so plainly and let reconciliation
            // surface it; a retry loop here would only hide it.
            sagas.failCompensation(saga.id(),
                    "Compensation " + step + " failed with " + code + ": " + message);
            log.error("Saga {} needs manual intervention: compensation {} failed ({})",
                    saga.id(), step, message);
            return;
        }

        List<String> succeededForward = sagas.findSteps(saga.id()).stream()
                .filter(row -> SagaPlan.FORWARD.equals(row.kind()))
                .filter(row -> SagaStepRow.SUCCEEDED.equals(row.status()))
                .map(SagaStepRow::step)
                .toList();

        if (SagaPlan.compensationsFor(succeededForward).isEmpty()) {
            // Nothing happened yet that anyone needs to hear about. A clean
            // decline, not a compensation.
            sagas.fail(saga.id(), code, message);
            log.info("Saga {} failed at {} with nothing to undo: {}", saga.id(), step, code);
            return;
        }

        sagas.startCompensating(saga.id(), code, message);
        log.info("Saga {} failed at {} ({}); compensating {}", saga.id(), step, code,
                SagaPlan.compensationsFor(succeededForward));
    }

    /**
     * Create the step row on first sight, with the body it will send forever.
     *
     * Re-read afterwards rather than trusting what was just built: if a previous
     * attempt already created this step, the insert did nothing and the command
     * on the row is the ORIGINAL one. Sending the freshly built body instead
     * would be exactly the recomputed-command bug the persisted column exists to
     * prevent -- and the Ledger would answer 422 IDEMPOTENCY_KEY_REUSED forever
     * rather than failing in any way that looks like a failure.
     */
    private SagaStepRow ensureStep(PaymentSaga saga, SagaStepExecutor executor) {
        String step = executor.step();
        sagas.createStepIfAbsent(saga.id(), step,
                SagaPlan.sequenceOf(step), SagaPlan.kindOf(step),
                com.spademoney.payments.ledger.LedgerIdempotencyKeys.forStep(saga.id().toString(), step),
                serialize(executor.buildCommand(saga)));

        return sagas.findStep(saga.id(), step)
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step + " vanished after being created for saga " + saga.id()));
    }

    private static boolean succeeded(List<SagaStepRow> steps, String step) {
        return steps.stream().anyMatch(row ->
                row.step().equals(step) && SagaStepRow.SUCCEEDED.equals(row.status()));
    }

    private String serialize(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}

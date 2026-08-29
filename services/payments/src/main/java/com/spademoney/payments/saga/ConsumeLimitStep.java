package com.spademoney.payments.saga;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.spademoney.payments.limit.PaymentLimitService;

/**
 * Step 2: check and consume the payer's cap, in Payments' own database.
 *
 * <h2>Why this step is the point of the whole milestone</h2>
 * It is the only step whose effect commits <em>here</em>. Steps 1 and 3 are
 * commands to another service with another database, and Postgres offers no
 * transaction spanning the two — deliberately, since they are separate databases
 * precisely so that shortcut is unavailable. So this local commit sits between
 * two remote effects that it cannot be atomic with, and when it refuses, the
 * hold taken in step 1 is already real.
 *
 * That is the moment a compensating transaction becomes the only option. Not a
 * rollback: a new command, issued forwards, that happens to undo.
 *
 * <h2>No local retries</h2>
 * A cap decision is deterministic — it depends on committed rows in a database
 * this service owns. Exceeded now means exceeded in a second. A retryable
 * outcome would only be right for infrastructure failures, and those arrive as
 * exceptions that the driver's own error handling already turns into a retry via
 * the lease.
 */
@Component
class ConsumeLimitStep implements SagaStepExecutor {

    private final PaymentLimitService limits;

    ConsumeLimitStep(PaymentLimitService limits) {
        this.limits = limits;
    }

    @Override
    public String step() {
        return SagaPlan.CONSUME_LIMIT;
    }

    /**
     * Persisted for symmetry and for the audit trail. This step sends nothing
     * over a network, so the body has no fingerprint to match — but "every step
     * stores the command it acted on" is a rule worth having no exceptions to,
     * and the row is what reconciliation reads to see what the saga believed it
     * was consuming.
     */
    @Override
    public Object buildCommand(PaymentSaga saga) {
        return Map.of(
                "accountId", saga.payerAccountId(),
                "amountMinor", saga.amountMinor(),
                "currency", saga.currency());
    }

    @Override
    public StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command) {
        PaymentLimitService.Decision decision =
                limits.consume(saga.id(), saga.payerAccountId(), saga.amountMinor(), saga.currency());

        return switch (decision) {
            case PaymentLimitService.Decision.Unlimited ignored ->
                new StepOutcome.Succeeded(Map.of("limit", "none"));

            case PaymentLimitService.Decision.Consumed consumed ->
                new StepOutcome.Succeeded(Map.of(
                        "capMinor", consumed.capMinor(),
                        "consumedMinor", consumed.consumedMinor()));

            case PaymentLimitService.Decision.Exceeded exceeded ->
                new StepOutcome.Terminal("PAYMENT_LIMIT_EXCEEDED",
                        "Account %d has %d of %d %s already committed and cannot add %d".formatted(
                                saga.payerAccountId(), exceeded.consumedMinor(), exceeded.capMinor(),
                                saga.currency(), exceeded.requestedMinor()));

            case PaymentLimitService.Decision.CurrencyMismatch mismatch ->
                new StepOutcome.Terminal("CURRENCY_MISMATCH",
                        "Account %d has a cap in %s; this payment is in %s".formatted(
                                saga.payerAccountId(), mismatch.capCurrency(),
                                mismatch.requestedCurrency()));
        };
    }
}

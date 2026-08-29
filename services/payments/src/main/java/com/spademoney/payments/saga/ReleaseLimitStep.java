package com.spademoney.payments.saga;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.spademoney.payments.limit.PaymentLimitService;

/**
 * Compensation for {@link ConsumeLimitStep}: give the payer's cap back.
 *
 * Runs BEFORE the void, because compensations unwind in reverse order. Here that
 * ordering means there is never a moment where the cap is free but the funds are
 * still reserved — a customer retrying immediately after a decline would
 * otherwise pass the cap check and then fail on funds their own abandoned hold
 * was still holding.
 *
 * Idempotent by predicate rather than by bookkeeping: the update only touches
 * rows not already released, so a redelivered or retried compensation is a
 * no-op. It cannot fail for a reason retrying would fix, and it cannot fail for
 * a reason retrying would not — which is what a compensation ought to look like,
 * and is why the risky one is the void.
 */
@Component
class ReleaseLimitStep implements SagaStepExecutor {

    private final PaymentLimitService limits;

    ReleaseLimitStep(PaymentLimitService limits) {
        this.limits = limits;
    }

    @Override
    public String step() {
        return SagaPlan.RELEASE_LIMIT;
    }

    @Override
    public Object buildCommand(PaymentSaga saga) {
        return Map.of("sagaId", saga.id().toString());
    }

    @Override
    public StepOutcome execute(PaymentSaga saga, String idempotencyKey, String command) {
        int released = limits.release(saga.id());
        return new StepOutcome.Succeeded(Map.of("releasedRows", released));
    }
}

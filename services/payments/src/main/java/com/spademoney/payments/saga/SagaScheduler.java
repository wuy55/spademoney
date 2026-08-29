package com.spademoney.payments.saga;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock, kept separate from the work -- the same split as the Ledger's
 * sweeper and outbox relay.
 *
 * {@link SagaDriver} is always a bean, so tests drive it one tick at a time and
 * can assert on the state between steps. A background thread advancing sagas
 * mid-assertion would make every "after authorize but before capture" test a
 * race, and those are the states worth asserting on.
 */
@Component
@ConditionalOnProperty(prefix = "spademoney.saga", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class SagaScheduler {

    private final SagaDriver driver;

    SagaScheduler(SagaDriver driver) {
        this.driver = driver;
    }

    @Scheduled(fixedDelayString = "${spademoney.saga.interval:PT0.25S}")
    void drive() {
        driver.runOnce();
    }
}

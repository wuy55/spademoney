package com.spademoney.ledger.hold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock, kept separate from the work.
 *
 * HoldExpirySweeper is always a bean and can be driven directly; only this
 * trigger is conditional. That is what lets the test suite exercise sweeping
 * deterministically -- a background thread relabelling holds mid-test would make
 * every "expired but still marked ACTIVE" assertion a race.
 */
@Component
@ConditionalOnProperty(prefix = "spademoney.holds.sweeper", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class HoldExpiryScheduler {

    private final HoldExpirySweeper sweeper;

    HoldExpiryScheduler(HoldExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${spademoney.holds.sweeper.interval:PT1M}")
    void sweep() {
        sweeper.sweepExpiredHolds();
    }
}

package com.spademoney.ledger.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock, kept separate from the work -- the same split as
 * {@code HoldExpiryScheduler}.
 *
 * {@link OutboxRelay} is always a bean and can be driven a tick at a time; only
 * this trigger is conditional. Tests therefore assert on exactly one drain
 * rather than racing a background thread that may or may not have run.
 *
 * {@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end
 * of the previous tick, so a slow broker cannot cause ticks to pile up on top of
 * each other. With {@code fixedRate} a relay that takes longer than its interval
 * would start overlapping itself, and the single-threaded ordering argument
 * would quietly stop being true.
 */
@Component
@ConditionalOnProperty(prefix = "spademoney.outbox.relay", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${spademoney.outbox.relay.interval:PT1S}")
    void drain() {
        relay.drainOnce();
    }
}

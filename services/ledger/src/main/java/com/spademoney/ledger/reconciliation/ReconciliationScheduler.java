package com.spademoney.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a timer and says what it found.
 *
 * The clock is kept separate from the work, as with the sweeper and the relay,
 * so tests call the service directly and assert on a report rather than waiting
 * for one.
 *
 * A clean run logs at INFO and names every check. That is deliberate: a
 * reconciliation job that is silent when healthy is indistinguishable from one
 * that has stopped running, and the whole point of the job is to be believed.
 */
@Component
@ConditionalOnProperty(prefix = "spademoney.reconciliation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final LedgerReconciliationService reconciliation;

    ReconciliationScheduler(LedgerReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${spademoney.reconciliation.interval:PT1M}")
    void reconcile() {
        ReconciliationReport report = reconciliation.reconcile();

        if (report.healthy()) {
            log.info("Reconciliation clean: {} check(s) passed", report.checks().size());
            return;
        }
        report.failures().forEach(failure ->
                log.error("Reconciliation FAILED {}: {} ({} row(s))",
                        failure.name(), failure.detail(), failure.count()));
    }
}

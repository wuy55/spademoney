package com.spademoney.payments.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a timer and says what it found — including when it
 * found nothing.
 *
 * A job that is silent while healthy is indistinguishable from a job that has
 * stopped, and the entire value of reconciliation is that its clean answer can
 * be believed.
 */
@Component
@ConditionalOnProperty(prefix = "spademoney.reconciliation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final PaymentsReconciliationService reconciliation;

    ReconciliationScheduler(PaymentsReconciliationService reconciliation) {
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

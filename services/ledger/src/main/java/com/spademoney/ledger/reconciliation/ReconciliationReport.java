package com.spademoney.ledger.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The answer to "how do you know?".
 *
 * <h2>Why a report and not just an alert</h2>
 * The interesting property of a reconciliation job is not that it fires when
 * something is wrong — it is that it says, in a form a person can read, exactly
 * what was checked and what each check found. A job that only speaks up on
 * failure is indistinguishable from a job that is broken. Every check appears
 * here, passing or not, so a clean report is evidence rather than silence.
 *
 * <h2>Checks are independent of the code that maintains the invariant</h2>
 * Each check below re-derives its answer from the raw rows: entries, holds,
 * outbox. None of them calls the service that wrote those rows. That
 * independence is the whole value — a check implemented by asking the same code
 * whether it did its job correctly proves nothing at all.
 */
public record ReconciliationReport(
        String service,
        OffsetDateTime ranAt,
        boolean healthy,
        List<Check> checks) {

    /**
     * @param name   stable identifier, safe to grep for in a chaos script
     * @param passed whether the invariant held
     * @param count  how many rows violated it (0 when it held)
     * @param detail human-readable, and specific enough to start an investigation
     */
    public record Check(String name, boolean passed, long count, String detail) {

        public static Check pass(String name, String detail) {
            return new Check(name, true, 0, detail);
        }

        public static Check fail(String name, long count, String detail) {
            return new Check(name, false, count, detail);
        }

        /**
         * The common shape: a query that returns violating rows, where zero rows
         * is the healthy answer.
         */
        public static Check ofViolations(String name, long violations, String whenClean, String whenBroken) {
            return violations == 0
                    ? pass(name, whenClean)
                    : fail(name, violations, whenBroken);
        }
    }

    public static ReconciliationReport of(String service, List<Check> checks) {
        return new ReconciliationReport(service, OffsetDateTime.now(),
                checks.stream().allMatch(Check::passed), checks);
    }

    public List<Check> failures() {
        return checks.stream().filter(check -> !check.passed()).toList();
    }
}

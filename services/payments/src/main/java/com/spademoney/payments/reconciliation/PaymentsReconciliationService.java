package com.spademoney.payments.reconciliation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.spademoney.payments.ledger.LedgerClient;
import com.spademoney.payments.ledger.LedgerCommands;
import com.spademoney.payments.reconciliation.ReconciliationReport.Check;

/**
 * Does Payments' record of what happened agree with the Ledger's?
 *
 * <h2>This is the check the split made necessary</h2>
 * Inside one service a foreign key answers this question and it cannot be
 * wrong. Across the boundary there is no constraint that can reach — the
 * accounts and holds a saga names live in another database, which is exactly
 * why {@code payment_limits.account_id} is deliberately not a foreign key. So
 * referential integrity becomes something that has to be VERIFIED rather than
 * enforced, periodically and after the fact. That trade is the price of the
 * split, and this class is where the bill is paid.
 *
 * <h2>The local checks are about the state machine; the remote ones about truth</h2>
 * Stuck sagas, unreleased limits and escalations are answerable from this
 * database alone. Whether a COMPLETED saga's transaction actually exists is not,
 * and no amount of local bookkeeping can substitute for asking.
 *
 * <h2>Remote checks are bounded</h2>
 * Confirming every saga ever run would mean an unbounded number of HTTP calls
 * inside one report, which turns a diagnostic into an outage. The most recent
 * window is sampled instead. That makes this check a smoke alarm rather than a
 * proof over all history — worth stating rather than glossing, and it is why the
 * chaos script reconciles immediately after the run it cares about.
 */
@Service
public class PaymentsReconciliationService {

    private final JdbcClient jdbcClient;
    private final LedgerClient ledger;
    private final Duration stuckAfter;
    private final int remoteSample;

    public PaymentsReconciliationService(JdbcClient jdbcClient, LedgerClient ledger,
            @Value("${spademoney.reconciliation.stuck-after:PT2M}") Duration stuckAfter,
            @Value("${spademoney.reconciliation.remote-sample:50}") int remoteSample) {
        this.jdbcClient = jdbcClient;
        this.ledger = ledger;
        this.stuckAfter = stuckAfter;
        this.remoteSample = remoteSample;
    }

    public ReconciliationReport reconcile() {
        List<Check> checks = new ArrayList<>();
        checks.add(noStuckSagas());
        checks.add(nothingNeedsAHuman());
        checks.add(everyFailedSagaReleasedItsLimit());
        checks.add(noTerminalSagaLeftAStepInFlight());
        checks.add(everyCompletedPaymentExistsInTheLedger());
        checks.add(everyCompensatedPaymentReleasedItsHold());
        return ReconciliationReport.of("payments", checks);
    }

    // ------------------------------------------------------------ local checks

    /**
     * A saga that has not moved in a long time.
     *
     * The driver retries with backoff and gives up after a bounded number of
     * attempts, so a genuinely stuck saga should be impossible — which is
     * precisely why it is worth checking. "Impossible" here rests on the driver
     * running at all; if the scheduler is dead, every in-flight payment sits
     * still and nothing else in this service would notice.
     */
    private Check noStuckSagas() {
        List<String> stuck = jdbcClient.sql("""
                SELECT id || ' (' || status || ', updated ' || updated_at || ')'
                  FROM sagas
                 WHERE status IN ('RUNNING','COMPENSATING')
                   AND updated_at < now() - (?::bigint * interval '1 second')
                 ORDER BY updated_at ASC
                """).param(stuckAfter.toSeconds()).query(String.class).list();

        return Check.ofViolations("NO_STUCK_SAGAS", stuck.size(),
                "no saga has been in flight longer than " + stuckAfter.toSeconds() + "s",
                "sagas that have not advanced: " + stuck);
    }

    /**
     * Sagas that could not undo what they had already done.
     *
     * This is the one failure this system admits it cannot resolve on its own:
     * funds are reserved in the Ledger that Payments tried and failed to
     * release, or a hold turned out to have been captured. Surfacing it here is
     * the difference between a system with a known dead end and a system that
     * hides one.
     */
    private Check nothingNeedsAHuman() {
        List<String> escalated = jdbcClient.sql("""
                SELECT id || ': ' || COALESCE(failure_message, failure_code)
                  FROM sagas WHERE failure_code = 'COMPENSATION_FAILED'
                """).query(String.class).list();

        return Check.ofViolations("NO_ESCALATED_SAGAS", escalated.size(),
                "no saga is waiting on manual intervention",
                "sagas whose compensation failed: " + escalated);
    }

    /**
     * A payment that failed must not still be holding the payer's cap.
     *
     * The limit is Payments' own state, so nothing outside this service will
     * ever notice it leaking. A payer whose cap is silently consumed by declined
     * payments is refused later for a reason nobody can explain, and there is no
     * error anywhere to lead an investigator to it.
     */
    private Check everyFailedSagaReleasedItsLimit() {
        List<String> leaked = jdbcClient.sql("""
                SELECT c.saga_id || ' (account ' || c.account_id || ', '
                       || c.amount_minor || ' ' || c.currency || ')'
                  FROM limit_consumptions c JOIN sagas s ON s.id = c.saga_id
                 WHERE c.released_at IS NULL
                   AND s.status IN ('FAILED','COMPENSATED')
                """).query(String.class).list();

        return Check.ofViolations("FAILED_SAGAS_RELEASED_THEIR_LIMIT", leaked.size(),
                "no failed payment is still holding a spending cap",
                "consumptions still held by failed sagas: " + leaked);
    }

    /**
     * A finished saga with a step still PENDING means the state machine reached
     * a terminal state by a route the plan does not describe. Cheap to check,
     * and it would catch a whole class of driver bugs that produce no other
     * symptom.
     */
    private Check noTerminalSagaLeftAStepInFlight() {
        long dangling = jdbcClient.sql("""
                SELECT count(*)
                  FROM saga_steps st JOIN sagas s ON s.id = st.saga_id
                 WHERE s.status IN ('COMPLETED','COMPENSATED','FAILED')
                   AND st.status = 'PENDING'
                """).query(Long.class).single();

        return Check.ofViolations("NO_DANGLING_STEPS", dangling,
                "every finished saga finished all of its steps",
                dangling + " step(s) left PENDING on a saga that has already ended");
    }

    // ----------------------------------------------------------- remote checks

    /**
     * The cross-boundary check: Payments says it captured, so the Ledger should
     * know about the transaction.
     *
     * A COMPLETED saga naming a transaction the Ledger has never heard of would
     * mean money reported as moved that did not move — the single worst outcome
     * this project is built to rule out. It has never happened and cannot easily
     * happen, and that is not a reason to stop asking.
     */
    private Check everyCompletedPaymentExistsInTheLedger() {
        record Completed(String sagaId, Long transactionId) {
        }

        List<Completed> recent = jdbcClient.sql("""
                SELECT id::text AS id, ledger_transaction_id
                  FROM sagas WHERE status = 'COMPLETED'
                 ORDER BY updated_at DESC LIMIT ?
                """).param(remoteSample)
                .query((rs, n) -> new Completed(rs.getString("id"),
                        rs.getObject("ledger_transaction_id", Long.class)))
                .list();

        List<String> problems = new ArrayList<>();
        for (Completed saga : recent) {
            if (saga.transactionId() == null) {
                problems.add(saga.sagaId() + ": completed with no ledger transaction id");
                continue;
            }
            try {
                ledger.get("/transfers/" + saga.transactionId(), Object.class);
            } catch (RuntimeException e) {
                problems.add(saga.sagaId() + ": ledger transaction "
                        + saga.transactionId() + " could not be confirmed (" + e.getMessage() + ")");
            }
        }

        return Check.ofViolations("COMPLETED_PAYMENTS_EXIST_IN_LEDGER", problems.size(),
                "every recent completed payment resolves to a real ledger transaction ("
                        + recent.size() + " sampled)",
                "completed payments the ledger cannot confirm: " + problems);
    }

    /**
     * The mirror: Payments says it gave the money back, so the Ledger's hold
     * must no longer be reserving anything.
     *
     * ACTIVE means the compensation did not take effect and the payer's funds
     * are still tied up behind a payment that was declined. CAPTURED is worse —
     * the payment was reported as failed and the money moved anyway.
     */
    private Check everyCompensatedPaymentReleasedItsHold() {
        record Compensated(String sagaId, Long holdId) {
        }

        List<Compensated> recent = jdbcClient.sql("""
                SELECT id::text AS id, hold_id
                  FROM sagas
                 WHERE status IN ('COMPENSATED','FAILED') AND hold_id IS NOT NULL
                 ORDER BY updated_at DESC LIMIT ?
                """).param(remoteSample)
                .query((rs, n) -> new Compensated(rs.getString("id"),
                        rs.getObject("hold_id", Long.class)))
                .list();

        List<String> problems = new ArrayList<>();
        for (Compensated saga : recent) {
            try {
                LedgerCommands.HoldResult hold =
                        ledger.get("/holds/" + saga.holdId(), LedgerCommands.HoldResult.class);
                if ("ACTIVE".equals(hold.status())) {
                    problems.add(saga.sagaId() + ": hold " + saga.holdId()
                            + " is still ACTIVE after the payment failed");
                } else if ("CAPTURED".equals(hold.status())) {
                    problems.add(saga.sagaId() + ": hold " + saga.holdId()
                            + " was CAPTURED although the payment is " + "reported as failed");
                }
            } catch (RuntimeException e) {
                problems.add(saga.sagaId() + ": hold " + saga.holdId()
                        + " could not be read (" + e.getMessage() + ")");
            }
        }

        return Check.ofViolations("FAILED_PAYMENTS_RELEASED_THEIR_HOLD", problems.size(),
                "every recent failed payment left its hold released (" + recent.size() + " sampled)",
                "failed payments whose hold is unresolved: " + problems);
    }
}

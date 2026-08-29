package com.spademoney.ledger.reconciliation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.reconciliation.ReconciliationReport.Check;

/**
 * Re-derives the Ledger's invariants from the raw rows and reports on them.
 *
 * <h2>These checks duplicate constraints on purpose</h2>
 * The zero-sum invariant is already enforced by a deferred constraint trigger,
 * and no account can go negative because every debit path checks available
 * balance under a lock. So why check again?
 *
 * Because the enforcement and the check fail in different ways. A trigger
 * protects rows written through it; it says nothing about rows written by a
 * migration, by a fix applied at 3am, or by a future code path that took a
 * shortcut. And a bug in the enforcement is invisible to the enforcement. The
 * value of this class is that it agrees with nothing — it reads entries and
 * holds and does the arithmetic itself.
 *
 * <h2>It runs in ONE read-only transaction</h2>
 * Otherwise the checks see different moments. A transfer committing between the
 * zero-sum query and the negative-balance query would let the report describe a
 * state that never existed, and the resulting "finding" would be a phantom that
 * disappears the moment anyone investigates it. One snapshot, one story.
 */
@Service
public class LedgerReconciliationService {

    /**
     * Grace before a lapsed hold counts as orphaned. The sweeper is housekeeping
     * and is allowed to be a little behind; the finding is meant to catch a
     * sweeper that has STOPPED, not one that is mid-tick.
     */
    private final Duration sweeperGrace;

    /**
     * Grace before an unpublished outbox row counts as a stuck relay. Same
     * reasoning: the relay is always slightly behind by design, and a check that
     * fired on that would be noise.
     */
    private final Duration relayGrace;

    private final JdbcClient jdbcClient;

    public LedgerReconciliationService(JdbcClient jdbcClient,
            @Value("${spademoney.reconciliation.sweeper-grace:PT2M}") Duration sweeperGrace,
            @Value("${spademoney.reconciliation.relay-grace:PT1M}") Duration relayGrace) {
        this.jdbcClient = jdbcClient;
        this.sweeperGrace = sweeperGrace;
        this.relayGrace = relayGrace;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<Check> checks = new ArrayList<>();
        checks.add(globalZeroSum());
        checks.add(everyTransactionBalances());
        checks.add(noNegativeBalances());
        checks.add(reservationsAreCovered());
        checks.add(noOverRefunds());
        checks.add(noOrphanedHolds());
        checks.add(outboxIsDraining());
        return ReconciliationReport.of("ledger", checks);
    }

    /**
     * The headline: across the whole ledger, per currency, credits equal debits.
     *
     * This is the number to put on screen after a chaos run. It cannot be true
     * "by accident" — every posting writes both sides in one statement, so a
     * non-zero total means either a half-written transaction survived a crash
     * (it cannot; entries are inserted atomically) or something wrote entries
     * outside the ledger service. Either would be worth knowing about
     * immediately.
     */
    private Check globalZeroSum() {
        List<String> broken = jdbcClient.sql("""
                SELECT currency || '=' || SUM(CASE direction WHEN 'CREDIT' THEN amount_minor
                                                             ELSE -amount_minor END)::text
                  FROM entries
                 GROUP BY currency
                HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount_minor
                                          ELSE -amount_minor END) <> 0
                """).query(String.class).list();

        return Check.ofViolations("GLOBAL_ZERO_SUM", broken.size(),
                "credits equal debits in every currency",
                "currencies whose entries do not net to zero: " + broken);
    }

    /**
     * The same arithmetic, per transaction. Enforced at commit by the deferred
     * entries_balanced trigger; re-derived here because a trigger only governs
     * rows that went through it.
     */
    private Check everyTransactionBalances() {
        long unbalanced = jdbcClient.sql("""
                SELECT count(*) FROM (
                    SELECT transaction_id
                      FROM entries
                     GROUP BY transaction_id, currency
                    HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount_minor
                                              ELSE -amount_minor END) <> 0
                ) s
                """).query(Long.class).single();

        return Check.ofViolations("EVERY_TRANSACTION_BALANCED", unbalanced,
                "every transaction nets to zero per currency",
                unbalanced + " transaction(s) do not net to zero");
    }

    /**
     * No USER_WALLET may hold a negative balance (ADR-014).
     *
     * <h2>Scoped to wallets, and the scope is the interesting part</h2>
     * The first version of this check had no WHERE clause and fired on every
     * healthy ledger, because CASH is negative by construction. That is not a
     * bug in the ledger, it is what double-entry means: money entering the
     * system is a credit to somebody's wallet and a debit to CASH, so CASH
     * carries the negative of everything ever funded. Same for CLEARING and
     * FEES. Those accounts are the system's side of the books and are supposed
     * to go negative; a rule forbidding it would forbid the ledger from working.
     *
     * The rule that actually matters is about customer money: a wallet below
     * zero means someone spent funds they did not have, which is the failure
     * every debit path checks available balance to prevent.
     */
    private Check noNegativeBalances() {
        List<String> negative = jdbcClient.sql("""
                SELECT e.account_id || '=' || SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor
                                                                   ELSE -e.amount_minor END)::text
                  FROM entries e JOIN accounts a ON a.id = e.account_id
                 WHERE a.type = 'USER_WALLET'
                 GROUP BY e.account_id
                HAVING SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor
                                            ELSE -e.amount_minor END) < 0
                """).query(String.class).list();

        return Check.ofViolations("NO_NEGATIVE_WALLETS", negative.size(),
                "no user wallet has been driven below zero",
                "wallets with a negative posted balance: " + negative);
    }

    /**
     * The safety invariant every debit path exists to maintain:
     *
     * <pre>for every account: posted &gt;= sum(active, unexpired holds)</pre>
     *
     * If this ever fails, money has been reserved that is not there — which is
     * precisely the state that lets a capture post entries with nothing behind
     * them. It is checked here rather than trusted because it is an emergent
     * property of several independent code paths (transfer, authorize, capture,
     * refund) all doing their part, and emergent properties are exactly the ones
     * a single careless change breaks.
     */
    private Check reservationsAreCovered() {
        List<String> uncovered = jdbcClient.sql("""
                WITH posted AS (
                    SELECT account_id,
                           SUM(CASE direction WHEN 'CREDIT' THEN amount_minor
                                              ELSE -amount_minor END) AS balance
                      FROM entries GROUP BY account_id
                ), held AS (
                    SELECT account_id, SUM(amount_minor) AS reserved
                      FROM holds
                     WHERE status = 'ACTIVE' AND expires_at > now()
                     GROUP BY account_id
                )
                SELECT h.account_id || ': held ' || h.reserved || ' > posted '
                       || COALESCE(p.balance, 0)
                  FROM held h LEFT JOIN posted p ON p.account_id = h.account_id
                 WHERE h.reserved > COALESCE(p.balance, 0)
                """).query(String.class).list();

        return Check.ofViolations("RESERVATIONS_COVERED", uncovered.size(),
                "every active hold is backed by a posted balance",
                "accounts reserving more than they hold: " + uncovered);
    }

    /**
     * Refunded-so-far is always derived from entries and never cached, so this
     * check is the closest thing this schema has to "cached equals derived":
     * it verifies that the derivation the refund path performs under a lock has
     * not been beaten by anything.
     */
    private Check noOverRefunds() {
        List<String> over = jdbcClient.sql("""
                WITH original AS (
                    SELECT t.id, SUM(e.amount_minor) AS amount
                      FROM transactions t JOIN entries e ON e.transaction_id = t.id
                     WHERE t.type <> 'REFUND' AND e.direction = 'DEBIT'
                     GROUP BY t.id
                ), refunded AS (
                    SELECT t.reverses_transaction_id AS id, SUM(e.amount_minor) AS amount
                      FROM transactions t JOIN entries e ON e.transaction_id = t.id
                     WHERE t.type = 'REFUND' AND e.direction = 'DEBIT'
                     GROUP BY t.reverses_transaction_id
                )
                SELECT o.id || ': refunded ' || r.amount || ' of ' || o.amount
                  FROM original o JOIN refunded r ON r.id = o.id
                 WHERE r.amount > o.amount
                """).query(String.class).list();

        return Check.ofViolations("NO_OVER_REFUNDS", over.size(),
                "no transaction has been refunded beyond its original amount",
                "over-refunded transactions: " + over);
    }

    /**
     * Holds still marked ACTIVE well past their deadline.
     *
     * Note what this is NOT: a money bug. A lapsed hold stops reserving funds
     * the moment it expires, because every consumer of a hold filters on
     * {@code expires_at > now()}. Correctness never depended on the sweeper. So
     * a finding here means the sweeper has stopped — an operational problem
     * worth a page, and one that would otherwise be completely silent.
     */
    private Check noOrphanedHolds() {
        long orphaned = jdbcClient.sql("""
                SELECT count(*) FROM holds
                 WHERE status = 'ACTIVE'
                   AND expires_at < now() - (?::bigint * interval '1 second')
                """).param(sweeperGrace.toSeconds()).query(Long.class).single();

        return Check.ofViolations("NO_ORPHANED_HOLDS", orphaned,
                "no hold has been left ACTIVE past its deadline",
                orphaned + " hold(s) lapsed more than " + sweeperGrace.toSeconds()
                        + "s ago and are still marked ACTIVE; the expiry sweeper may be stopped");
    }

    /**
     * Events that committed with their money but never reached the broker.
     *
     * Also not a money bug — the whole design intent of the outbox is that the
     * Ledger keeps working with the broker down and the backlog drains later. A
     * finding here means the backlog is not draining, so downstream services are
     * working from a story that is missing its most recent pages.
     */
    private Check outboxIsDraining() {
        long stuck = jdbcClient.sql("""
                SELECT count(*) FROM outbox
                 WHERE published_at IS NULL
                   AND occurred_at < now() - (?::bigint * interval '1 second')
                """).param(relayGrace.toSeconds()).query(Long.class).single();

        return Check.ofViolations("OUTBOX_DRAINING", stuck,
                "the outbox relay is keeping up",
                stuck + " event(s) have been unpublished for more than "
                        + relayGrace.toSeconds() + "s; the relay may be stuck or the broker down");
    }
}

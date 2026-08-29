package com.spademoney.ledger.reconciliation;

import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation, proved by breaking things.
 *
 * <h2>Half of these tests have to defeat the schema first</h2>
 * The zero-sum invariant is enforced by a deferred constraint trigger, so a
 * broken transaction cannot be written through the normal path — which is the
 * point of having the trigger, and is also why the checks look redundant until
 * you ask what they are for. They exist for state the trigger never saw: a
 * migration, a manual fix, a future code path that took a shortcut.
 *
 * The tests reproduce that by disabling the trigger, writing the bad rows, and
 * turning it back on. Anything less would be testing that the check compiles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerReconciliationTest {

    @Autowired
    private LedgerReconciliationService reconciliation;
    @Autowired
    private LedgerTransactionService ledger;
    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE outbox, holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        fund(payer, 100_000L);
    }

    @AfterEach
    void restoreTheSchemasDefences() {
        jdbcClient.sql("ALTER TABLE entries ENABLE TRIGGER entries_balanced").update();
    }

    @Test
    void anOrdinaryLedgerReconcilesCleanAndSaysWhatItChecked() {
        ledger.transfer(payer, payee, Money.of(2_500L, Currency.getInstance("USD")));

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.failures()).isEmpty();
        assertThat(report.healthy()).isTrue();
        assertThat(report.service()).isEqualTo("ledger");
        // Every check is reported, not just the failures. A job that speaks only
        // when something is wrong is indistinguishable from a job that has died.
        assertThat(names(report)).containsExactlyInAnyOrder(
                "GLOBAL_ZERO_SUM", "EVERY_TRANSACTION_BALANCED", "NO_NEGATIVE_WALLETS",
                "RESERVATIONS_COVERED", "NO_OVER_REFUNDS", "NO_ORPHANED_HOLDS",
                "OUTBOX_DRAINING");
    }

    /**
     * Money appearing from nowhere: a lone credit, with the constraint trigger
     * switched off so it can be written at all.
     *
     * This is the check that matters most and the one whose value is easiest to
     * dismiss. The trigger already prevents this — for rows it sees. The report
     * is what notices when something wrote rows it did not see.
     */
    @Test
    void aOneSidedPostingIsCaughtByBothArithmeticChecks() {
        jdbcClient.sql("ALTER TABLE entries DISABLE TRIGGER entries_balanced").update();
        Long txn = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, 'CREDIT', 5000, 'USD')
                """).params(txn, payee).update();

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.healthy()).isFalse();
        assertThat(failedNames(report))
                .contains("GLOBAL_ZERO_SUM", "EVERY_TRANSACTION_BALANCED");
        assertThat(check(report, "GLOBAL_ZERO_SUM").detail()).contains("USD=5000");
    }

    /**
     * A wallet driven below zero.
     *
     * The check is scoped to USER_WALLET on purpose: CASH is negative by
     * construction, because every funding is a credit to a wallet and a debit to
     * CASH. An unscoped version of this check fired on every healthy ledger --
     * which is how the scope came to be written down rather than assumed.
     */
    @Test
    void aWalletDrivenNegativeIsReportedWhileCashStayingNegativeIsNormal() {
        jdbcClient.sql("ALTER TABLE entries DISABLE TRIGGER entries_balanced").update();
        Long txn = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, 'DEBIT', 5000, 'USD')
                """).params(txn, payee).update();

        ReconciliationReport report = reconciliation.reconcile();
        assertThat(failedNames(report)).contains("NO_NEGATIVE_WALLETS");
        // The CASH account is at -100000 throughout this class and never appears.
        assertThat(check(report, "NO_NEGATIVE_WALLETS").detail()).doesNotContain(cash.toString() + "=");
    }

    /**
     * A hold reserving more than the account holds.
     *
     * Written straight into the table, because HoldService refuses to create one
     * -- it checks available balance under a lock. The invariant
     * "posted >= sum(active holds)" is emergent from several code paths agreeing,
     * and emergent properties are exactly the ones a careless change breaks
     * without any single place looking wrong.
     */
    @Test
    void aReservationLargerThanTheBalanceBehindItIsReported() {
        jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, 500000, 'USD', now() + interval '1 hour')
                """).params(payer, payee).update();

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).contains("RESERVATIONS_COVERED");
        assertThat(check(report, "RESERVATIONS_COVERED").detail()).contains("held 500000");
    }

    /**
     * A lapsed hold still marked ACTIVE long after its deadline: the sweeper has
     * stopped.
     *
     * Note this is NOT a money bug and the test does not treat it as one. Every
     * consumer of a hold filters on expires_at, so the balance was right the
     * whole time. What the check catches is an operational failure that would
     * otherwise be completely silent.
     */
    @Test
    void aSweeperThatHasStoppedIsReportedWithoutBeingCalledAMoneyBug() {
        jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, 1000, 'USD', now() - interval '1 hour')
                """).params(payer, payee).update();

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).containsExactly("NO_ORPHANED_HOLDS");
        // The arithmetic is untouched: an expired hold reserves nothing.
        assertThat(check(report, "GLOBAL_ZERO_SUM").passed()).isTrue();
        assertThat(check(report, "RESERVATIONS_COVERED").passed()).isTrue();
    }

    /**
     * Events that committed with their money and never reached the broker.
     *
     * Also not a money bug: the outbox is designed to accumulate while the
     * broker is down. A finding means the backlog has stopped draining, so
     * consumers are working from a story missing its last pages.
     */
    @Test
    void anOutboxThatHasStoppedDrainingIsReported() {
        ledger.transfer(payer, payee, Money.of(1_000L, Currency.getInstance("USD")));
        jdbcClient.sql("UPDATE outbox SET occurred_at = now() - interval '10 minutes'").update();

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).containsExactly("OUTBOX_DRAINING");
        assertThat(check(report, "OUTBOX_DRAINING").count()).isEqualTo(1);
    }

    /** A published event is not a backlog, however old it is. */
    @Test
    void aDrainedOutboxDoesNotCountHoweverOldTheEventsAre() {
        ledger.transfer(payer, payee, Money.of(1_000L, Currency.getInstance("USD")));
        jdbcClient.sql("""
                UPDATE outbox SET occurred_at = now() - interval '10 minutes', published_at = now()
                """).update();

        assertThat(reconciliation.reconcile().healthy()).isTrue();
    }

    private static List<String> names(ReconciliationReport report) {
        return report.checks().stream().map(ReconciliationReport.Check::name).toList();
    }

    private static List<String> failedNames(ReconciliationReport report) {
        return report.failures().stream().map(ReconciliationReport.Check::name).toList();
    }

    private static ReconciliationReport.Check check(ReconciliationReport report, String name) {
        return report.checks().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No check named " + name));
    }

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long accountId, long amountMinor) {
        Long txn = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, 'CREDIT', ?, 'USD'), (?, ?, 'DEBIT', ?, 'USD')
                """).params(txn, accountId, amountMinor, txn, cash, amountMinor).update();
    }
}

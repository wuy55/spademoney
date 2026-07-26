package com.spademoney.ledger.hold;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.query.AccountBalances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sweeper is housekeeping, and these tests are mostly about proving that.
 * The load-bearing assertion is that sweeping changes NO balance: if it did,
 * a paused job would be a money bug.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldExpirySweeperTest {

    @Autowired
    private HoldExpirySweeper sweeper;
    @Autowired
    private HoldService holds;
    @Autowired
    private AccountBalances balances;
    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        fund(payer, 100_000L);
    }

    @Test
    void sweepingRelabelsLapsedHoldsAndLeavesLiveOnesAlone() {
        long lapsed = insertHold(30_000L, "-1 second");
        HoldResponse live = holds.authorize(new AuthorizeRequest(payer, payee, 20_000L, "USD", 3600L));

        assertThat(sweeper.sweepExpiredHolds()).isEqualTo(1);

        assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
        assertThat(statusOf(live.holdId())).isEqualTo("ACTIVE");
    }

    // THE point of the sweeper's design: it is not on the correctness path.
    @Test
    void sweepingChangesNoBalanceWhatsoever() {
        insertHold(30_000L, "-1 second");

        long postedBefore = balances.posted(payer);
        long heldBefore = balances.held(payer);
        long availableBefore = balances.available(payer);

        // The lapsed hold already reserves nothing, before any job has run.
        assertThat(heldBefore).isZero();
        assertThat(availableBefore).isEqualTo(100_000L);

        assertThat(sweeper.sweepExpiredHolds()).isEqualTo(1);

        assertThat(balances.posted(payer)).isEqualTo(postedBefore);
        assertThat(balances.held(payer)).isEqualTo(heldBefore);
        assertThat(balances.available(payer)).isEqualTo(availableBefore);
    }

    @Test
    void sweepingIsIdempotentAndFindsNothingOnASecondPass() {
        insertHold(30_000L, "-1 second");

        assertThat(sweeper.sweepExpiredHolds()).isEqualTo(1);
        assertThat(sweeper.sweepExpiredHolds()).as("nothing left to do").isZero();
        assertThat(sweeper.sweepExpiredHolds()).isZero();
    }

    @Test
    void sweepingNeverTouchesAlreadyResolvedHolds() {
        HoldResponse voided = holds.authorize(new AuthorizeRequest(payer, payee, 10_000L, "USD", 3600L));
        holds.voidHold(voided.holdId());

        HoldResponse captured = holds.authorize(new AuthorizeRequest(payer, payee, 10_000L, "USD", 3600L));
        holds.capture(captured.holdId(), 10_000L);

        assertThat(sweeper.sweepExpiredHolds()).isZero();

        // Terminal states are final: had the sweeper tried, the
        // holds_terminal_is_final trigger would have rejected it outright.
        assertThat(statusOf(voided.holdId())).isEqualTo("VOIDED");
        assertThat(statusOf(captured.holdId())).isEqualTo("CAPTURED");
    }

    @Test
    void anExpiredHoldIsTerminalAndCanNoLongerBeCapturedOrVoided() {
        long lapsed = insertHold(30_000L, "-1 second");
        sweeper.sweepExpiredHolds();

        assertThatThrownBy(() -> holds.capture(lapsed, 30_000L))
                .isInstanceOf(HoldNotActiveException.class);
        assertThatThrownBy(() -> holds.voidHold(lapsed))
                .isInstanceOf(HoldNotActiveException.class);
    }

    // Before the sweep the same capture is refused for a DIFFERENT reason. Both
    // are 422, but they are different facts and the client is told which.
    @Test
    void beforeSweepingAnExpiredHoldReportsExpiredRatherThanResolved() {
        long lapsed = insertHold(30_000L, "-1 second");

        assertThat(statusOf(lapsed)).as("no sweeper has run yet").isEqualTo("ACTIVE");
        assertThatThrownBy(() -> holds.capture(lapsed, 30_000L))
                .isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void sweepingRespectsTheConfiguredBatchSizeAcrossPasses() {
        for (int i = 0; i < 3; i++) {
            insertHold(1_000L, "-1 second");
        }

        int firstPass = sweeper.sweepExpiredHolds();
        assertThat(firstPass).isEqualTo(3);
        assertThat(expiredCount()).isEqualTo(3);
    }

    // ---------- helpers ----------

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long walletId, long amountMinor) {
        Long txnId = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """).params(txnId, cash, amountMinor, txnId, walletId, amountMinor).update();
    }

    /** Inserted directly: HoldService refuses to authorize an already-lapsed hold. */
    private long insertHold(long amountMinor, String interval) {
        return jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, ?, 'USD', now() + ?::interval)
                RETURNING id
                """).params(payer, payee, amountMinor, interval).query(Long.class).single();
    }

    private String statusOf(long holdId) {
        return jdbcClient.sql("SELECT status FROM holds WHERE id=?")
                .param(holdId).query(String.class).single();
    }

    private int expiredCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM holds WHERE status='EXPIRED'")
                .query(Integer.class).single();
    }
}

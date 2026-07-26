package com.spademoney.ledger.hold;

import java.util.Currency;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.query.AccountBalances;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capture: the step that turns a reservation into money that actually moved.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldCaptureTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private HoldService holds;
    @Autowired
    private LedgerTransactionService ledger;
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

    // Full capture: the reservation becomes a real posting, and only now does
    // posted balance move.
    @Test
    void fullCapturePostsTheAuthorizedAmount() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        assertThat(balances.posted(payer)).isEqualTo(100_000L);

        CaptureResponse captured = holds.capture(hold.holdId(), 30_000L);

        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(captured.capturedMinor()).isEqualTo(30_000L);
        assertThat(captured.releasedMinor()).isZero();
        assertThat(captured.transactionId()).isNotNull();

        assertThat(balances.posted(payer)).isEqualTo(70_000L);
        assertThat(balances.posted(payee)).isEqualTo(30_000L);
        assertThat(balances.held(payer)).as("a captured hold no longer reserves").isZero();
        assertThat(balances.available(payer)).isEqualTo(70_000L);
    }

    // The gas-pump case: authorize 750.00, capture 431.00, the rest goes back.
    @Test
    void partialCapturePostsLessAndReleasesTheRemainder() {
        HoldResponse hold = holds.authorize(authorize(75_000L));
        assertThat(balances.available(payer)).isEqualTo(25_000L);

        CaptureResponse captured = holds.capture(hold.holdId(), 43_100L);

        assertThat(captured.capturedMinor()).isEqualTo(43_100L);
        assertThat(captured.releasedMinor()).isEqualTo(31_900L);

        // Only the captured amount moved; the remaining 31_900 was never money,
        // just a reservation, so there is nothing to reverse.
        assertThat(balances.posted(payer)).isEqualTo(56_900L);
        assertThat(balances.posted(payee)).isEqualTo(43_100L);
        assertThat(balances.held(payer)).isZero();
        assertThat(balances.available(payer)).isEqualTo(56_900L);
        assertThat(entryCount()).as("exactly one double-entry for the capture").isEqualTo(4);
    }

    @Test
    void captureIsRecordedAsACaptureNotATransfer() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        CaptureResponse captured = holds.capture(hold.holdId(), 30_000L);

        String type = jdbcClient.sql("SELECT type FROM transactions WHERE id = ?")
                .param(captured.transactionId()).query(String.class).single();

        // Without `type`, a capture and a plain transfer are indistinguishable
        // after the fact -- they post identical entries.
        assertThat(type).isEqualTo("CAPTURE");

        Long linked = jdbcClient.sql("SELECT captured_transaction_id FROM holds WHERE id = ?")
                .param(hold.holdId()).query(Long.class).single();
        assertThat(linked).isEqualTo(captured.transactionId());
    }

    @Test
    void capturingMoreThanWasAuthorizedIsRejected() {
        HoldResponse hold = holds.authorize(authorize(30_000L));

        assertThatThrownBy(() -> holds.capture(hold.holdId(), 30_001L))
                .isInstanceOf(CaptureExceedsHoldException.class);

        // The whole attempt rolled back: the hold is untouched and still usable.
        assertThat(statusOf(hold.holdId())).isEqualTo("ACTIVE");
        assertThat(balances.posted(payer)).isEqualTo(100_000L);
        assertThat(transactionCount()).as("no orphan CAPTURE transaction survives").isEqualTo(1);
    }

    @Test
    void aHoldCanOnlyBeCapturedOnce() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        holds.capture(hold.holdId(), 10_000L);

        assertThatThrownBy(() -> holds.capture(hold.holdId(), 10_000L))
                .isInstanceOf(HoldNotActiveException.class);

        assertThat(balances.posted(payee)).as("the second capture posted nothing").isEqualTo(10_000L);
    }

    @Test
    void capturingAVoidedHoldIsRejected() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        holds.voidHold(hold.holdId());

        assertThatThrownBy(() -> holds.capture(hold.holdId(), 30_000L))
                .isInstanceOf(HoldNotActiveException.class);
    }

    @Test
    void capturingAnUnknownHoldIsNotFound() {
        assertThatThrownBy(() -> holds.capture(999_999L, 1_000L))
                .isInstanceOf(HoldNotFoundException.class);
    }

    // ---- the hazard: expiry is enforced by a predicate, not by the sweeper ----

    // An expired hold still READS as ACTIVE until a sweeper relabels it, but it
    // stopped reserving funds the moment it lapsed. Capturing on status alone
    // would post entries with nothing behind them.
    @Test
    void capturingAnExpiredHoldIsRejectedEvenThoughItStillReadsActive() {
        long holdId = insertExpiredHold(30_000L);

        assertThat(statusOf(holdId)).as("no sweeper has run").isEqualTo("ACTIVE");
        assertThat(balances.held(payer)).as("but it reserves nothing").isZero();

        assertThatThrownBy(() -> holds.capture(holdId, 30_000L))
                .isInstanceOf(HoldExpiredException.class);
    }

    // The full failure this closes: hold expires, its funds get spent by someone
    // else, then a late capture arrives. Without the expires_at predicate in the
    // compare-and-set this posts 30_000 the payer no longer has, driving the
    // balance negative -- money created from nothing.
    @Test
    void aLateCaptureCannotMintMoneyAfterTheFundsWereSpent() {
        long holdId = insertExpiredHold(30_000L);

        // The reservation has lapsed, so the whole balance is spendable again.
        assertThat(balances.available(payer)).isEqualTo(100_000L);
        ledger.transfer(payer, payee, Money.of(100_000L, USD));
        assertThat(balances.posted(payer)).isZero();

        assertThatThrownBy(() -> holds.capture(holdId, 30_000L))
                .isInstanceOf(HoldExpiredException.class);

        assertThat(balances.posted(payer)).as("no account may go negative").isNotNegative();
        assertThat(globalSignedSum()).as("global ledger still nets to zero").isZero();
    }

    // Capture and void race on one hold. The single edge out of ACTIVE means
    // Postgres serializes them on the row and exactly one wins.
    @Test
    void captureAndVoidRacingOnOneHoldResolveItExactlyOnce() throws InterruptedException {
        final int ATTEMPTS = 12;
        HoldResponse hold = holds.authorize(authorize(30_000L));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(ATTEMPTS);
        AtomicInteger winners = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < ATTEMPTS; i++) {
            final boolean isCapture = (i % 2 == 0);
            new Thread(() -> {
                try {
                    startGate.await();
                    if (isCapture) {
                        holds.capture(hold.holdId(), 30_000L);
                    } else {
                        holds.voidHold(hold.holdId());
                    }
                    winners.incrementAndGet();
                } catch (HoldNotActiveException expected) {
                    // legal: someone else resolved it first
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("threads hung").isTrue();
        assertThat(unexpected).as("only already-resolved is a legal failure").isEmpty();
        assertThat(winners.get()).as("exactly one of capture/void may win").isEqualTo(1);

        // Whichever won, the invariant survives and posted covers what remains held.
        assertThat(balances.posted(payer)).isGreaterThanOrEqualTo(balances.held(payer));
        assertThat(globalSignedSum()).isZero();
    }

    // ---------- helpers ----------

    private AuthorizeRequest authorize(long amountMinor) {
        return new AuthorizeRequest(payer, payee, amountMinor, "USD", 3600L);
    }

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

    /** Inserted directly: HoldService refuses to authorize a hold this short. */
    private long insertExpiredHold(long amountMinor) {
        return jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, ?, 'USD', now() - interval '1 second')
                RETURNING id
                """).params(payer, payee, amountMinor).query(Long.class).single();
    }

    private String statusOf(long holdId) {
        return jdbcClient.sql("SELECT status FROM holds WHERE id=?")
                .param(holdId).query(String.class).single();
    }

    private int entryCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM entries").query(Integer.class).single();
    }

    private int transactionCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM transactions").query(Integer.class).single();
    }

    private long globalSignedSum() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                FROM entries
                """).query(Long.class).single();
    }
}

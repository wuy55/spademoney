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
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hold moves AVAILABLE without moving POSTED, and every debit path respects
 * it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldAuthorizationTest {

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

    // An authorization moves no money. This is the whole reason holds are not
    // ledger entries -- there is nothing in the ledger to undo when it expires.
    @Test
    void authorizingReservesFundsWithoutPostingAnything() {
        long entriesBefore = entryCount();

        HoldResponse hold = holds.authorize(authorize(30_000L));

        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(entryCount()).as("authorize must post no entries").isEqualTo(entriesBefore);
        assertThat(balances.posted(payer)).isEqualTo(100_000L);
        assertThat(balances.held(payer)).isEqualTo(30_000L);
        assertThat(balances.available(payer)).isEqualTo(70_000L);
    }

    // Posted funds that are reserved cannot be spent elsewhere.
    @Test
    void aHoldBlocksATransferThatPostedBalanceAloneWouldAllow() {
        holds.authorize(authorize(30_000L));

        assertThatThrownBy(() -> ledger.transfer(payer, payee, Money.of(80_000L, USD)))
                .isInstanceOf(InsufficientFundsException.class);

        // 80_000 <= posted (100_000) but > available (70_000). Checking posted
        // instead of available would let this through and break the invariant.
        assertThat(balances.posted(payer)).isEqualTo(100_000L);

        // Up to available still works.
        ledger.transfer(payer, payee, Money.of(70_000L, USD));
        assertThat(balances.available(payer)).isZero();
    }

    @Test
    void voidingReleasesTheReservationAndPostsNothing() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        long entriesBefore = entryCount();

        HoldResponse voided = holds.voidHold(hold.holdId());

        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(entryCount()).as("void must post no entries").isEqualTo(entriesBefore);
        assertThat(balances.posted(payer)).isEqualTo(100_000L);
        assertThat(balances.available(payer)).isEqualTo(100_000L);
    }

    @Test
    void voidingATerminalHoldIsRejected() {
        HoldResponse hold = holds.authorize(authorize(30_000L));
        holds.voidHold(hold.holdId());

        assertThatThrownBy(() -> holds.voidHold(hold.holdId()))
                .isInstanceOf(HoldNotActiveException.class);
    }

    @Test
    void voidingAnUnknownHoldIsNotFound() {
        assertThatThrownBy(() -> holds.voidHold(999_999L))
                .isInstanceOf(HoldNotFoundException.class);
    }

    // The auth window is client-supplied but bounded by the SERVICE, because the
    // bound is policy rather than request shape. @Positive on the DTO only
    // rejects nonsense; these are the rules.
    @Test
    void anAuthorizationWindowOutsideTheAllowedBandIsRejected() {
        // Too short to be actionable: the merchant could not capture in time.
        assertThatThrownBy(() -> holds.authorize(
                new AuthorizeRequest(payer, payee, 1_000L, "USD", 59L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresInSeconds");

        // Longer than 7 days: an unbounded hold silently strands a payer's money.
        assertThatThrownBy(() -> holds.authorize(
                new AuthorizeRequest(payer, payee, 1_000L, "USD", 604_801L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresInSeconds");
    }

    @Test
    void theBoundsThemselvesAreInclusive() {
        assertThat(holds.authorize(new AuthorizeRequest(payer, payee, 1_000L, "USD", 60L)).status())
                .isEqualTo("ACTIVE");
        assertThat(holds.authorize(new AuthorizeRequest(payer, payee, 1_000L, "USD", 604_800L)).status())
                .isEqualTo("ACTIVE");
    }

    // expires_at is computed by Postgres, not this JVM, so the deadline and every
    // now() it is compared against come from ONE clock. Asserting the stored
    // value against the DB's own clock is what pins that down: if authorize went
    // back to OffsetDateTime.now(), a skewed app clock would move this window.
    @Test
    void theExpiryDeadlineComesFromTheDatabaseClock() {
        HoldResponse hold = holds.authorize(new AuthorizeRequest(payer, payee, 1_000L, "USD", 3600L));

        Boolean withinTolerance = jdbcClient.sql("""
                SELECT expires_at BETWEEN now() + interval '3595 seconds'
                                      AND now() + interval '3600 seconds'
                  FROM holds WHERE id = ?
                """).param(hold.holdId()).query(Boolean.class).single();

        assertThat(withinTolerance).as("deadline must be one hour on the DB clock").isTrue();
    }

    // The read path must not trust the sweeper. An expired hold stops
    // counting the moment it expires, whether or not any job has run.
    @Test
    void anExpiredHoldStopsReservingEvenThoughItIsStillMarkedActive() {
        jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, 30000, 'USD', now() - interval '1 second')
                """).params(payer, payee).update();

        assertThat(statusOfOnlyHold()).as("no sweeper has run").isEqualTo("ACTIVE");
        assertThat(balances.held(payer)).isZero();
        assertThat(balances.available(payer)).isEqualTo(100_000L);
    }

    // Concurrency, independent oracle: payer holds 100_000; 10 threads each try
    // to reserve 15_000. If the payer lock did nothing, all ten would read the
    // same available balance and reserve 150_000 -- over-reserving by 50_000.
    // With correct serialization exactly floor(100_000/15_000) = 6 succeed. The
    // expected value is arithmetic, not read back from the code under test.
    @Test
    void concurrentAuthorizationsCannotOverReserve() throws InterruptedException {
        final long AMOUNT = 15_000L;
        final int THREADS = 10;
        final int EXPECTED = (int) (100_000L / AMOUNT);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger successes = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    holds.authorize(authorize(AMOUNT));
                    successes.incrementAndGet();
                } catch (InsufficientFundsException expected) {
                    // legal once the available balance is exhausted
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("threads hung").isTrue();
        assertThat(unexpected).as("only insufficient-funds is a legal failure").isEmpty();
        assertThat(successes.get()).isEqualTo(EXPECTED);
        assertThat(balances.held(payer)).isEqualTo(EXPECTED * AMOUNT);

        // The safety invariant, stated directly.
        assertThat(balances.posted(payer))
                .as("posted must always cover every active hold")
                .isGreaterThanOrEqualTo(balances.held(payer));
        assertThat(balances.available(payer)).isNotNegative();
    }

    // Mixed load: authorizations and transfers racing on one account must still
    // leave posted >= held. This is the property capture will rely on.
    @Test
    void postedAlwaysCoversActiveHoldsUnderMixedLoad() throws InterruptedException {
        final int THREADS = 12;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            final boolean isTransfer = (i % 2 == 0);
            new Thread(() -> {
                try {
                    startGate.await();
                    if (isTransfer) {
                        ledger.transfer(payer, payee, Money.of(12_000L, USD));
                    } else {
                        holds.authorize(authorize(12_000L));
                    }
                } catch (InsufficientFundsException expected) {
                    // legal
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("threads hung -- possible deadlock").isTrue();
        assertThat(unexpected).isEmpty();
        assertThat(balances.posted(payer)).isGreaterThanOrEqualTo(balances.held(payer));
        assertThat(balances.available(payer)).isNotNegative();
        assertThat(globalSignedSum()).as("global ledger still nets to zero").isZero();
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

    private int entryCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM entries").query(Integer.class).single();
    }

    private long globalSignedSum() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                FROM entries
                """).query(Long.class).single();
    }

    private String statusOfOnlyHold() {
        return jdbcClient.sql("SELECT status FROM holds WHERE account_id=?")
                .param(payer).query(String.class).single();
    }
}

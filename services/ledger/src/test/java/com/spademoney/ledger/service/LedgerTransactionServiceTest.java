package com.spademoney.ledger.service;

import java.util.Currency;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerTransactionServiceTest {

    @Autowired
    private LedgerTransactionService ledgerService;

    @Autowired
    private JdbcClient jdbcClient;

    private Long account1Id;
    private Long account2Id;
    private Long accountCash;

    @BeforeEach
    void setup() {
        // Create three accounts for testing
        account1Id = createAccount("USER_WALLET", "USD");
        account2Id = createAccount("USER_WALLET", "USD");
        accountCash = createAccount("CASH", "USD");

        // Fund account1 and account2 with $1000 each
        fundAccount(account1Id, 100_000L); // 1000 USD in cents
        fundAccount(account2Id, 100_000L);
    }

    private Long createAccount(String type, String currency) {
        return jdbcClient
                .sql("INSERT INTO accounts(type, currency) VALUES (?, ?) RETURNING id")
                .params(type, currency)
                .query(Long.class)
                .single();
    }

    private void fundAccount(Long accountId, Long amountMinor) {
        Long txnId = jdbcClient
                .sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class)
                .single();

        // Both entries inserted atomically in ONE statement
        // Trigger doesn't fire until both rows exist
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT', ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """)
                .params(txnId, accountCash, amountMinor, txnId, accountId, amountMinor)
                .update();
    }

    private Long getBalance(Long accountId) {
        return jdbcClient
                .sql("SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    // ========== TEST 1: Simple successful transfer ==========
    @Test
    void testSimpleTransfer() {
        Money amount = Money.of(5000L, Currency.getInstance("USD"));
        Long txnId = ledgerService.transfer(account1Id, account2Id, amount);

        assertThat(txnId).isNotNull();
        assertThat(getBalance(account1Id)).isEqualTo(95_000L);
        assertThat(getBalance(account2Id)).isEqualTo(105_000L);
    }

    // ========== TEST 2: Overdraft prevention ==========
    @Test
    void testOverdraftPrevention() {
        Money tooMuch = Money.of(200_000L, Currency.getInstance("USD"));

        assertThatThrownBy(() -> ledgerService.transfer(account1Id, account2Id, tooMuch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(getBalance(account1Id)).isEqualTo(100_000L);
        assertThat(getBalance(account2Id)).isEqualTo(100_000L);
    }

    // ========== TEST 3: Concurrency — N threads drain one account; ordered
    // pessimistic locking must serialize them so NO overdraft slips through.
    //
    // FORCED-NEGATIVE SIZING: source holds 100_000; 10 threads each try to move
    // 15_000 → 150_000 demanded > 100_000 available. If the FOR UPDATE lock did
    // nothing, threads would read the same stale balance, all pass the overdraft
    // check, and drive source to 100_000 − 150_000 = −50_000.
    //
    // INDEPENDENT ORACLE: with correct single-source serialization each success
    // removes exactly 15_000, so exactly floor(100_000 / 15_000) = 6 succeed,
    // regardless of thread ordering (after 6, only 10_000 < 15_000 remains). The
    // expected value is derived from arithmetic, NOT read back from the code
    // under test — remove the lock and this test fails.
    // ==========
    @Test
    void testConcurrencyNoOverdraft() throws InterruptedException {
        final long INITIAL = 100_000L;
        final long AMOUNT = 15_000L;
        final int THREADS = 10;
        final int EXPECTED_SUCCESSES = (int) (INITIAL / AMOUNT); // 6, computed independently

        Long source = createAccount("USER_WALLET", "USD");
        Long dest = createAccount("USER_WALLET", "USD");
        fundAccount(source, INITIAL);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    startGate.await(); // release all threads at once → tighten the race window
                    ledgerService.transfer(source, dest, Money.of(AMOUNT, Currency.getInstance("USD")));
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException overdraft) {
                    // legal outcome once the account is drained
                } catch (Throwable t) {
                    unexpected.add(t); // anything else is a real failure, surfaced below
                } finally {
                    done.countDown();
                }
            }).start();
        }

        startGate.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        assertThat(finished).as("threads hung — possible deadlock").isTrue();
        assertThat(unexpected).as("transfers threw unexpected (non-overdraft) errors").isEmpty();

        long sourceBalance = getBalance(source);
        long destBalance = getBalance(dest);

        // Independent-oracle assertions — all deterministic:
        assertThat(successCount.get())
                .as("exactly floor(%d/%d)=%d transfers may succeed", INITIAL, AMOUNT, EXPECTED_SUCCESSES)
                .isEqualTo(EXPECTED_SUCCESSES);
        assertThat(sourceBalance)
                .as("source = initial minus exactly the successful debits")
                .isEqualTo(INITIAL - (long) EXPECTED_SUCCESSES * AMOUNT); // 10_000
        assertThat(destBalance)
                .isEqualTo((long) EXPECTED_SUCCESSES * AMOUNT); // 90_000
        assertThat(sourceBalance + destBalance)
                .as("money is conserved")
                .isEqualTo(INITIAL);
        assertThat(sourceBalance)
                .as("source must never go negative")
                .isGreaterThanOrEqualTo(0L);
    }

    // ========== TEST 4: Concurrency test — bidirectional transfers, no deadlock
    // ==========
    @Test
    void testConcurrencyBidirectional() throws InterruptedException {
        Long acctA = createAccount("USER_WALLET", "USD");
        Long acctB = createAccount("USER_WALLET", "USD");
        fundAccount(acctA, 100_000L);
        fundAccount(acctB, 100_000L);

        int numThreads = 5;
        CountDownLatch latch = new CountDownLatch(numThreads * 2);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        // Half the threads transfer A→B, half transfer B→A
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    Money amount = Money.of(1000L, Currency.getInstance("USD"));
                    ledgerService.transfer(acctA, acctB, amount);
                } catch (Throwable t) {
                    errors.add(t); // record instead of swallow
                } finally {
                    latch.countDown();
                }
            }).start();

            new Thread(() -> {
                try {
                    Money amount = Money.of(1000L, Currency.getInstance("USD"));
                    ledgerService.transfer(acctB, acctA, amount);
                } catch (Throwable t) {
                    errors.add(t); // record instead of swallow
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Should complete without deadlock
        boolean finished = latch.await(30, TimeUnit.SECONDS);
        assertThat(finished).as("threads did not finish — possible deadlock/hang").isTrue();

        // No transaction was killed (deadlock surfaces as a thrown exception here)
        assertThat(errors).as("transfers threw — e.g. deadlock").isEmpty();

        // Money conservation: equal bidirectional volume, totals unchanged
        long balanceA = getBalance(acctA);
        long balanceB = getBalance(acctB);
        assertThat(balanceA + balanceB).isEqualTo(200_000L); // 100k + 100k
    }
}
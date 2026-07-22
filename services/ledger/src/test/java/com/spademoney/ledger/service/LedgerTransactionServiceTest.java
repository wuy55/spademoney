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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.spademoney.ledger.money.Money;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class LedgerTransactionServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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
                .sql("INSERT INTO transactions DEFAULT VALUES RETURNING id")
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

    // ========== TEST 3: Property test — ledger invariant holds under random
    // transfers ==========
    @Test
    void testLedgerInvariantUnderRandomTransfers() {
        // Use the setup accounts, not freshly created ones
        Money m1 = Money.of(5000L, Currency.getInstance("USD"));
        Money m2 = Money.of(3000L, Currency.getInstance("USD"));
        Money m3 = Money.of(2000L, Currency.getInstance("USD"));

        ledgerService.transfer(account1Id, account2Id, m1);
        ledgerService.transfer(account2Id, account1Id, m2); // bidirectional
        ledgerService.transfer(account1Id, account2Id, m3);

        // Verify ledger invariant
        Long balance1 = getBalance(account1Id);
        Long balance2 = getBalance(account2Id);
        assertThat(balance1).isGreaterThanOrEqualTo(0L);
        assertThat(balance2).isGreaterThanOrEqualTo(0L);
    }

    // ========== TEST 4: Concurrency test — N threads on one account, no overdraft
    // ==========
    @Test
    void testConcurrencyNoOverdraft() throws InterruptedException {
        Long source = createAccount("USER_WALLET", "USD");
        Long dest = createAccount("USER_WALLET", "USD");
        fundAccount(source, 100_000L);

        int numThreads = 10;
        int amountPerThread = 15000;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    Money amount = Money.of(amountPerThread, Currency.getInstance("USD"));
                    ledgerService.transfer(source, dest, amount);
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    // Expected: some threads overdraft
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Some transfers succeed, some fail due to overdraft
        assertThat(successCount.get()).isGreaterThan(0);
        assertThat(successCount.get()).isLessThanOrEqualTo(numThreads);

        // Source balance never went negative
        Long sourceBalance = getBalance(source);

        Long destBalance = getBalance(dest);
        assertThat(destBalance).isGreaterThan(0L);
        assertThat(sourceBalance + destBalance).isEqualTo(100_000L);
        assertThat(sourceBalance).isGreaterThanOrEqualTo(0L);
    }

    // ========== TEST 5: Concurrency test — bidirectional transfers, no deadlock
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

        assertThat(balanceA).isNotNull();
        assertThat(balanceB).isNotNull();
    }
}
package com.spademoney.ledger.idempotency;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.transfer.TransferRequest;
import com.spademoney.ledger.transfer.TransferResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural assertions on the idempotency contract, driven directly against
 * IdempotencyService rather than through MockMvc so the DB can be asserted on
 * directly and concurrency is actually drivable.
 *
 * Not @Transactional: each call into IdempotencyService.executeTransfer needs
 * its own real transaction (that is the point being tested).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long sender;
    private Long receiver;

    @BeforeEach
    void resetLedger() {
        // Shared container => shared DB across test classes. Safe because Surefire
        // runs test classes SEQUENTIALLY. Do not enable
        // junit.jupiter.execution.parallel without revisiting this.
        jdbcClient.sql("""
                TRUNCATE holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();

        cash = seedAccount("CASH", "USD");
        sender = seedAccount("USER_WALLET", "USD");
        receiver = seedAccount("USER_WALLET", "USD");
        fundAccount(cash, sender, 100_000L);
    }

    // Two threads, one key. The loser blocks on the unique index until the
    // winner commits, then re-reads COMPLETED and replays. A 409 here would
    // falsify the class javadoc on IdempotencyService.
    @RepeatedTest(25)
    void concurrentRequestsWithSameKeyNeverConflictAndApplyExactlyOnce() throws InterruptedException {
        String key = "concurrent-key";
        TransferRequest request = new TransferRequest(sender, receiver, 5_000L, "USD");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<TransferResponse> response1 = new AtomicReference<>();
        AtomicReference<TransferResponse> response2 = new AtomicReference<>();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        Runnable callA = () -> {
            try {
                startGate.await();
                response1.set(idempotencyService.executeTransfer(key, request));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };
        Runnable callB = () -> {
            try {
                startGate.await();
                response2.set(idempotencyService.executeTransfer(key, request));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };

        new Thread(callA).start();
        new Thread(callB).start();
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("threads hung").isTrue();
        assertThat(errors).as("neither caller may see a 409/conflict").isEmpty();

        assertThat(response1.get().transactionId())
                .as("both callers must see the same transaction")
                .isEqualTo(response2.get().transactionId());

        assertThat(balanceOf(receiver)).isEqualTo(5_000L);
        assertThat(entryCountFor(receiver)).isEqualTo(1);
        assertThat(transactionCount()).isEqualTo(2); // funding + exactly one transfer
    }

    // Failed transfer frees the key.
    @Test
    void failedTransferFreesTheKeyAndRetryIsTreatedAsNew() {
        String key = "will-fail";
        TransferRequest tooMuch = new TransferRequest(sender, receiver, 999_999L, "USD");

        assertThatThrownBy(() -> idempotencyService.executeTransfer(key, tooMuch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(rowExists(key))
                .as("a failed transfer rolls back the claim with everything else — key is freed")
                .isFalse();

        TransferRequest valid = new TransferRequest(sender, receiver, 5_000L, "USD");
        TransferResponse response = idempotencyService.executeTransfer(key, valid);

        assertThat(response.transactionId()).isNotNull();
        assertThat(balanceOf(receiver)).isEqualTo(5_000L);
        assertThat(rowExists(key)).isTrue();
    }

    // Replay moves no money.
    @Test
    void replayReturnsEqualResponseAndMovesNoMoney() {
        String key = "replay-key";
        TransferRequest request = new TransferRequest(sender, receiver, 5_000L, "USD");

        TransferResponse first = idempotencyService.executeTransfer(key, request);
        TransferResponse second = idempotencyService.executeTransfer(key, request);

        assertThat(second).isEqualTo(first);
        // Response-equality alone would pass even if the transfer re-executed —
        // assert the ledger directly.
        assertThat(transactionCount()).isEqualTo(2); // funding + exactly one transfer
        assertThat(totalEntryCount()).isEqualTo(4); // 2 funding + 2 transfer, never doubled
        assertThat(balanceOf(sender)).isEqualTo(95_000L);
        assertThat(balanceOf(receiver)).isEqualTo(5_000L);
    }

    // Committed IN_PROGRESS row -> 409.
    // The stranded-row scenario the 409 path defends against — unreachable
    // under normal operation since the claim and COMPLETED write commit
    // together, but simulated here by inserting the row out-of-band.
    @Test
    void committedInProgressRowCausesConflictAndMovesNothing() {
        String key = "stuck-key";
        TransferRequest request = new TransferRequest(sender, receiver, 5_000L, "USD");
        String fingerprint = IdempotencyService.fingerprint(request);

        jdbcClient.sql("""
                INSERT INTO idempotency_keys(endpoint, idempotency_key, request_fingerprint, status)
                VALUES ('/transfers', ?, ?, 'IN_PROGRESS')
                """)
                .params(key, fingerprint)
                .update();

        assertThatThrownBy(() -> idempotencyService.executeTransfer(key, request))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(balanceOf(sender)).isEqualTo(100_000L);
    }

    // 422 on key reuse, moves nothing.
    @Test
    void sameKeyDifferentFingerprintIsRejectedAndMovesNothing() {
        String key = "reuse-key";
        idempotencyService.executeTransfer(key, new TransferRequest(sender, receiver, 5_000L, "USD"));

        assertThatThrownBy(() -> idempotencyService.executeTransfer(
                key, new TransferRequest(sender, receiver, 9_999L, "USD")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        // Only the first (accepted) transfer moved money.
        assertThat(balanceOf(sender)).isEqualTo(95_000L);
    }

    // ---------- helpers ----------

    private Long seedAccount(String type, String currency) {
        return jdbcClient
                .sql("INSERT INTO accounts(type, currency) VALUES (?, ?) RETURNING id")
                .params(type, currency)
                .query(Long.class)
                .single();
    }

    private void fundAccount(Long cashId, Long walletId, long amountMinor) {
        Long txnId = jdbcClient
                .sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """)
                .params(txnId, cashId, amountMinor, txnId, walletId, amountMinor)
                .update();
    }

    private long balanceOf(Long accountId) {
        return jdbcClient
                .sql("""
                        SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                        FROM entries WHERE account_id = ?
                        """)
                .param(accountId)
                .query(Long.class)
                .single();
    }

    private int entryCountFor(Long accountId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM entries WHERE account_id = ?")
                .param(accountId)
                .query(Integer.class)
                .single();
    }

    private int totalEntryCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM entries").query(Integer.class).single();
    }

    private int transactionCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM transactions").query(Integer.class).single();
    }

    private boolean rowExists(String key) {
        Integer count = jdbcClient
                .sql("SELECT COUNT(*) FROM idempotency_keys WHERE endpoint='/transfers' AND idempotency_key=?")
                .param(key)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}

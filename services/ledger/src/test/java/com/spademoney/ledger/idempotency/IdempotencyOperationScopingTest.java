package com.spademoney.ledger.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.hold.AuthorizeRequest;
import com.spademoney.ledger.hold.HoldResponse;
import com.spademoney.ledger.hold.HoldService;
import com.spademoney.ledger.hold.VoidHoldRequest;
import com.spademoney.ledger.idempotency.IdempotencyService.Outcome;
import com.spademoney.ledger.transfer.TransferRequest;
import com.spademoney.ledger.transfer.TransferResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency keys are scoped per operation.
 *
 * Two rules, and they pull in opposite directions:
 *   - the SAME key against DIFFERENT operations must not collide
 *   - the SAME key against the SAME operation with a different resource must
 *     be a 422, which is why resource ids live in the FINGERPRINT and never in
 *     the operation string
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyOperationScopingTest {

    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private HoldService holds;
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
    void oneKeyReusedAcrossDifferentOperationsDoesNotCollide() {
        String key = "shared-key";

        TransferResponse transfer = idempotency.executeTransfer(
                key, new TransferRequest(payer, payee, 5_000L, "USD"));

        HoldResponse hold = authorizeWith(key, 10_000L);

        assertThat(transfer.transactionId()).isNotNull();
        assertThat(hold.holdId()).isNotNull();
        assertThat(rowCount()).as("one row per (operation, key)").isEqualTo(2);
    }

    // A key names ONE logical request. Because the hold id is in the fingerprint
    // rather than the operation string, voiding a different hold with the same
    // key is caught instead of silently getting its own scope.
    @Test
    void oneKeyReusedAgainstADifferentHoldIsRejected() {
        HoldResponse first = authorizeWith("auth-1", 10_000L);
        HoldResponse second = authorizeWith("auth-2", 10_000L);

        String key = "void-key";
        idempotency.execute(IdempotencyService.OP_VOID, key, new VoidHoldRequest(first.holdId()),
                HoldResponse.class, 200, () -> Outcome.of(holds.voidHold(first.holdId())));

        assertThatThrownBy(() -> idempotency.execute(
                IdempotencyService.OP_VOID, key, new VoidHoldRequest(second.holdId()),
                HoldResponse.class, 200, () -> Outcome.of(holds.voidHold(second.holdId()))))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(statusOf(second.holdId())).as("the rejected void must not execute").isEqualTo("ACTIVE");
    }

    @Test
    void replayingAnAuthorizeCreatesOnlyOneHold() {
        HoldResponse first = authorizeWith("replay-key", 10_000L);
        HoldResponse second = authorizeWith("replay-key", 10_000L);

        assertThat(second).isEqualTo(first);
        assertThat(holdCount()).as("the replay must not create a second hold").isEqualTo(1);
    }

    // Authorize creates no transaction, so transaction_id stays NULL.
    @Test
    void anOperationWithNoTransactionCompletesWithNullTransactionId() {
        authorizeWith("no-txn-key", 10_000L);

        Integer nulls = jdbcClient.sql("""
                SELECT COUNT(*) FROM idempotency_keys
                 WHERE endpoint = ? AND status = 'COMPLETED' AND transaction_id IS NULL
                """).param(IdempotencyService.OP_AUTHORIZE).query(Integer.class).single();

        assertThat(nulls).isEqualTo(1);
    }

    // ---------- helpers ----------

    private HoldResponse authorizeWith(String key, long amountMinor) {
        AuthorizeRequest request = new AuthorizeRequest(payer, payee, amountMinor, "USD", 3600L);
        return idempotency.execute(IdempotencyService.OP_AUTHORIZE, key, request,
                HoldResponse.class, 201, () -> Outcome.of(holds.authorize(request)));
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

    private int rowCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM idempotency_keys").query(Integer.class).single();
    }

    private int holdCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM holds").query(Integer.class).single();
    }

    private String statusOf(long holdId) {
        return jdbcClient.sql("SELECT status FROM holds WHERE id=?")
                .param(holdId).query(String.class).single();
    }
}

package com.spademoney.ledger.hold;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold invariants are enforced by the DATABASE, not only by HoldService.
 * These tests bypass the service entirely and go at the SQL, because the point
 * being proven is that no code path -- including a future capture, a sweeper,
 * or a hand-run correction -- can violate them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldSchemaConstraintTest {

    @Autowired
    private JdbcClient jdbcClient;

    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
    }

    // A terminal hold can never be moved again. This is what makes capture a
    // compare-and-set rather than a read-decide-write under a lock.
    @Test
    void aResolvedHoldCanNeverBeUpdatedAgain() {
        long holdId = insertActiveHold(5_000L, "+1 hour");

        jdbcClient.sql("UPDATE holds SET status='VOIDED', resolved_at=now() WHERE id=?")
                .param(holdId).update();

        assertThatThrownBy(() -> jdbcClient
                .sql("UPDATE holds SET status='CAPTURED', resolved_at=now() WHERE id=?")
                .param(holdId).update())
                .isInstanceOf(DataAccessException.class);

        assertThat(statusOf(holdId)).isEqualTo("VOIDED");
    }

    // Status and the resolution columns can never disagree.
    @Test
    void capturedHoldWithoutATransactionIsRejected() {
        long holdId = insertActiveHold(5_000L, "+1 hour");

        assertThatThrownBy(() -> jdbcClient
                .sql("UPDATE holds SET status='CAPTURED', resolved_at=now() WHERE id=?")
                .param(holdId).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void activeHoldWithAResolvedAtIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at, resolved_at)
                VALUES (?, ?, 1000, 'USD', now() + interval '1 hour', now())
                """).params(payer, payee).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void holdToSelfIsRejected() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, 1000, 'USD', now() + interval '1 hour')
                """).params(payer, payer).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void zeroOrNegativeHoldAmountIsRejected() {
        for (long bad : new long[] { 0L, -1L }) {
            assertThatThrownBy(() -> jdbcClient.sql("""
                    INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                    VALUES (?, ?, ?, 'USD', now() + interval '1 hour')
                    """).params(payer, payee, bad).update())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // Only a REFUND may name a reversed transaction, and it MUST name one.
    @Test
    void reversesTransactionIdIsRestrictedToRefunds() {
        Long original = jdbcClient
                .sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();

        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO transactions(type, reverses_transaction_id) VALUES ('TRANSFER', ?)")
                .param(original).update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO transactions(type) VALUES ('REFUND')")
                .update())
                .isInstanceOf(DataAccessException.class);

        Long refund = jdbcClient
                .sql("INSERT INTO transactions(type, reverses_transaction_id) VALUES ('REFUND', ?) RETURNING id")
                .param(original).query(Long.class).single();
        assertThat(refund).isNotNull();
    }

    // The V2 migration dropped the DEFAULT deliberately: a capture silently
    // recorded as a TRANSFER is a reporting bug that surfaces months later.
    @Test
    void transactionTypeMustBeStatedExplicitly() {
        assertThatThrownBy(() -> jdbcClient.sql("INSERT INTO transactions DEFAULT VALUES").update())
                .isInstanceOf(DataAccessException.class);
    }

    // ---------- helpers ----------

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private long insertActiveHold(long amountMinor, String interval) {
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

    @SuppressWarnings("unused")
    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}

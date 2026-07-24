package com.spademoney.ledger.idempotency;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Postgres behavior the whole idempotency design (see
 * IdempotencyService) is shaped around: a unique violation aborts the WHOLE
 * transaction (25P02 on every later statement on that connection), so catching
 * DuplicateKeyException and re-reading the winner's row is unreachable without
 * a savepoint. This is why the service uses ON CONFLICT DO NOTHING instead of a
 * catch block.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresTransactionAbortTest {

    @Autowired
    private JdbcClient jdbcClient;

    // @Transactional here does double duty: it gives the whole test one
    // connection (required to observe the abort), and Spring rolls it back
    // automatically afterwards so this never pollutes the shared DB.
    @Test
    @Transactional
    void uniqueViolationAbortsTheWholeTransaction() {
        insertAbortTestKey();

        DataAccessException duplicate = catchThrown(this::insertAbortTestKey);
        assertThat(sqlState(duplicate))
                .as("re-inserting the same PK must be a unique violation")
                .isEqualTo("23505");

        DataAccessException aborted = catchThrown(() -> {
            jdbcClient.sql("SELECT 1").query(Integer.class).single();
        });
        assertThat(sqlState(aborted))
                .as("the SAME connection refuses even a trivial SELECT until rollback")
                .isEqualTo("25P02");
    }

    private void insertAbortTestKey() {
        jdbcClient.sql("""
                INSERT INTO idempotency_keys(endpoint, idempotency_key, request_fingerprint, status)
                VALUES ('/transfers', 'abort-test-key', 'fp', 'IN_PROGRESS')
                """).update();
    }

    private DataAccessException catchThrown(Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            return e;
        }
        throw new AssertionError("expected a DataAccessException but none was thrown");
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}

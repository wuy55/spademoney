package com.spademoney.payments.saga;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement that touches the saga tables.
 *
 * Hand-written SQL, as everywhere else in this project (ADR-008). For a state
 * machine this matters more than usual: the claim below is a lease, and a lease
 * expressed as an ORM save is a lease nobody can review.
 */
@Repository
public class SagaRepository {

    private static final String SAGA_COLUMNS = """
            id, idempotency_key, request_fingerprint, status,
            payer_account_id, payee_account_id, amount_minor, currency,
            hold_id, ledger_transaction_id, failure_code, failure_message
            """;

    private final JdbcClient jdbcClient;

    public SagaRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // ---------------------------------------------------------------- create

    /**
     * Claim the caller's idempotency key for a new saga.
     *
     * ON CONFLICT DO NOTHING rather than a lookup followed by an insert: two
     * concurrent requests carrying the same key would both find nothing and both
     * proceed. The unique index serializes them instead, and the loser is told
     * so by a row count of zero. Same shape as the Ledger's idempotency claim,
     * for the same reason.
     *
     * @return true if this call created the saga
     */
    public boolean tryCreate(UUID id, String idempotencyKey, String fingerprint,
            long payerAccountId, long payeeAccountId, long amountMinor, String currency) {
        return jdbcClient.sql("""
                INSERT INTO sagas (id, idempotency_key, request_fingerprint, status,
                                   payer_account_id, payee_account_id, amount_minor, currency)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .params(id, idempotencyKey, fingerprint,
                        payerAccountId, payeeAccountId, amountMinor, currency)
                .update() == 1;
    }

    public Optional<PaymentSaga> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT " + SAGA_COLUMNS + " FROM sagas WHERE idempotency_key = ?")
                .param(idempotencyKey)
                .query(SagaRepository::mapSaga)
                .optional();
    }

    public Optional<PaymentSaga> findById(UUID id) {
        return jdbcClient.sql("SELECT " + SAGA_COLUMNS + " FROM sagas WHERE id = ?")
                .param(id)
                .query(SagaRepository::mapSaga)
                .optional();
    }

    // ----------------------------------------------------------------- drive

    /**
     * Take out a lease on up to {@code batchSize} sagas that are due.
     *
     * <h2>A lease, not a lock</h2>
     * The driver is about to make an HTTP call that can take seconds, and
     * holding a database transaction open across a network call is how a slow
     * peer turns into a database problem. So the claim is its own short
     * transaction: it pushes {@code next_attempt_at} into the future and lets
     * go. If the driver then dies mid-step, nothing is stuck — the lease simply
     * expires and the next tick picks the saga up again. Recovery uses the same
     * code path as the happy case, which means it is exercised by every test
     * rather than only by the one that remembers to simulate a crash.
     *
     * {@code FOR UPDATE SKIP LOCKED} keeps two drivers off the same row during
     * the claim itself. It is not what makes concurrent drivers safe — the
     * deterministic step keys are — it just stops them wasting effort.
     */
    public List<PaymentSaga> claimDue(int batchSize, Duration lease) {
        return jdbcClient.sql("""
                UPDATE sagas
                   SET next_attempt_at = now() + (?::bigint * interval '1 millisecond'),
                       updated_at = now()
                 WHERE id IN (
                       SELECT id FROM sagas
                        WHERE status IN ('RUNNING','COMPENSATING')
                          AND next_attempt_at <= now()
                        ORDER BY next_attempt_at ASC
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                 )
                RETURNING
                """ + SAGA_COLUMNS)
                .params(lease.toMillis(), batchSize)
                .query(SagaRepository::mapSaga)
                .list();
    }

    public void scheduleAt(UUID sagaId, OffsetDateTime when) {
        jdbcClient.sql("UPDATE sagas SET next_attempt_at = ?, updated_at = now() WHERE id = ?")
                .params(when, sagaId)
                .update();
    }

    /** Make the saga due immediately, so the next tick continues it. */
    public void scheduleNow(UUID sagaId) {
        jdbcClient.sql("UPDATE sagas SET next_attempt_at = now(), updated_at = now() WHERE id = ?")
                .param(sagaId)
                .update();
    }

    // ----------------------------------------------------------------- steps

    public Optional<SagaStepRow> findStep(UUID sagaId, String step) {
        return jdbcClient.sql("""
                SELECT step, kind, status, idempotency_key, command::text AS command,
                       result::text AS result, attempts, last_error
                  FROM saga_steps WHERE saga_id = ? AND step = ?
                """)
                .params(sagaId, step)
                .query(SagaRepository::mapStep)
                .optional();
    }

    public List<SagaStepRow> findSteps(UUID sagaId) {
        return jdbcClient.sql("""
                SELECT step, kind, status, idempotency_key, command::text AS command,
                       result::text AS result, attempts, last_error
                  FROM saga_steps WHERE saga_id = ? ORDER BY seq ASC
                """)
                .param(sagaId)
                .query(SagaRepository::mapStep)
                .list();
    }

    /**
     * Create the step with the body it will send for the rest of its life.
     *
     * ON CONFLICT DO NOTHING because a driver that crashed after creating the
     * step but before running it will come back and try to create it again --
     * and must find the ORIGINAL command, not a freshly built one. Overwriting
     * here would reintroduce exactly the recomputed-body problem the column
     * exists to prevent.
     */
    public void createStepIfAbsent(UUID sagaId, String step, int seq, String kind,
            String idempotencyKey, String command) {
        jdbcClient.sql("""
                INSERT INTO saga_steps (saga_id, step, seq, kind, status, idempotency_key, command)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?::jsonb)
                ON CONFLICT (saga_id, step) DO NOTHING
                """)
                .params(sagaId, step, seq, kind, idempotencyKey, command)
                .update();
    }

    public void markStepSucceeded(UUID sagaId, String step, String result) {
        jdbcClient.sql("""
                UPDATE saga_steps
                   SET status = 'SUCCEEDED', result = ?::jsonb, attempts = attempts + 1,
                       last_error = NULL, completed_at = now()
                 WHERE saga_id = ? AND step = ?
                """)
                .params(result, sagaId, step)
                .update();
    }

    public void markStepFailed(UUID sagaId, String step, String error) {
        jdbcClient.sql("""
                UPDATE saga_steps
                   SET status = 'FAILED', attempts = attempts + 1, last_error = ?, completed_at = now()
                 WHERE saga_id = ? AND step = ?
                """)
                .params(error, sagaId, step)
                .update();
    }

    /** A retryable attempt: count it, keep the reason, leave the step PENDING. */
    public int recordAttempt(UUID sagaId, String step, String error) {
        return jdbcClient.sql("""
                UPDATE saga_steps
                   SET attempts = attempts + 1, last_error = ?
                 WHERE saga_id = ? AND step = ?
                RETURNING attempts
                """)
                .params(error, sagaId, step)
                .query(Integer.class)
                .single();
    }

    // ---------------------------------------------------------------- status

    public void recordHold(UUID sagaId, long holdId) {
        jdbcClient.sql("UPDATE sagas SET hold_id = ?, updated_at = now() WHERE id = ?")
                .params(holdId, sagaId).update();
    }

    public void recordTransaction(UUID sagaId, long transactionId) {
        jdbcClient.sql("UPDATE sagas SET ledger_transaction_id = ?, updated_at = now() WHERE id = ?")
                .params(transactionId, sagaId).update();
    }

    public void complete(UUID sagaId) {
        jdbcClient.sql("""
                UPDATE sagas SET status = 'COMPLETED', next_attempt_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'RUNNING'
                """).param(sagaId).update();
    }

    /**
     * Turn the saga around. Conditional on RUNNING so a second caller -- an
     * inbox event arriving at the same moment as a step failure -- cannot start
     * compensating a saga that is already compensating or finished.
     */
    public boolean startCompensating(UUID sagaId, String code, String message) {
        return jdbcClient.sql("""
                UPDATE sagas
                   SET status = 'COMPENSATING', failure_code = ?, failure_message = ?,
                       next_attempt_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'RUNNING'
                """).params(code, message, sagaId).update() == 1;
    }

    public boolean fail(UUID sagaId, String code, String message) {
        return jdbcClient.sql("""
                UPDATE sagas
                   SET status = 'FAILED', failure_code = COALESCE(failure_code, ?),
                       failure_message = COALESCE(failure_message, ?),
                       next_attempt_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('RUNNING','COMPENSATING')
                """).params(code, message, sagaId).update() == 1;
    }

    /**
     * A compensation could not be completed. This overwrites whatever reason the
     * saga was already carrying, because it is now the more urgent fact: "the
     * payment was over the limit" stops being the headline once the funds that
     * were held for it could not be released. The original reason is kept in the
     * message rather than discarded.
     */
    public void failCompensation(UUID sagaId, String message) {
        jdbcClient.sql("""
                UPDATE sagas
                   SET status = 'FAILED',
                       failure_code = 'COMPENSATION_FAILED',
                       failure_message = ? || ' (original failure: '
                                           || COALESCE(failure_code, 'unknown') || ')',
                       next_attempt_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('RUNNING','COMPENSATING')
                """).params(message, sagaId).update();
    }

    public void markCompensated(UUID sagaId) {
        jdbcClient.sql("""
                UPDATE sagas SET status = 'COMPENSATED', next_attempt_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'COMPENSATING'
                """).param(sagaId).update();
    }

    public Optional<PaymentSaga> findByHoldId(long holdId) {
        return jdbcClient.sql("SELECT " + SAGA_COLUMNS + " FROM sagas WHERE hold_id = ?")
                .param(holdId)
                .query(SagaRepository::mapSaga)
                .optional();
    }

    // ------------------------------------------------------------------ maps

    private static PaymentSaga mapSaga(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PaymentSaga(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getString("status"),
                rs.getLong("payer_account_id"),
                rs.getLong("payee_account_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("hold_id", Long.class),
                rs.getObject("ledger_transaction_id", Long.class),
                rs.getString("failure_code"),
                rs.getString("failure_message"));
    }

    private static SagaStepRow mapStep(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SagaStepRow(
                rs.getString("step"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("command"),
                rs.getString("result"),
                rs.getInt("attempts"),
                rs.getString("last_error"));
    }
}

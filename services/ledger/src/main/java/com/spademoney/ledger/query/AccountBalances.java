package com.spademoney.ledger.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single definition of posted, held and available balance.
 *
 * Deliberately NOT annotated @Transactional: every caller already has a
 * transaction, and the write paths must compute available INSIDE the same
 * transaction that holds the payer's row lock. A propagation boundary here
 * would read outside that lock, reopening the gap between reading a balance
 * and posting against it.
 *
 * Both figures are derived, never stored (ADR-002). A read-side cache would
 * have to be reconciled against these queries before it could be trusted.
 */
@Component
public class AccountBalances {

    private static final String POSTED_SQL = """
            SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
              FROM entries WHERE account_id = ?
            """;

    /**
     * expires_at is checked HERE, not left to the sweeper. Correctness comes
     * from this predicate; the scheduled job only keeps the table tidy. A
     * paused sweeper must never become a money bug.
     */
    private static final String HELD_SQL = """
            SELECT COALESCE(SUM(amount_minor), 0)
              FROM holds
             WHERE account_id = ? AND status = 'ACTIVE' AND expires_at > now()
            """;

    private final JdbcClient jdbcClient;

    public AccountBalances(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long posted(long accountId) {
        return jdbcClient.sql(POSTED_SQL).param(accountId).query(Long.class).single();
    }

    public long held(long accountId) {
        return jdbcClient.sql(HELD_SQL).param(accountId).query(Long.class).single();
    }

    /** What can be spent right now. Every debit path must check THIS, not posted. */
    public long available(long accountId) {
        return posted(accountId) - held(accountId);
    }
}

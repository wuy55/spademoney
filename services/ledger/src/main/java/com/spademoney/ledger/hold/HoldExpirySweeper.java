package com.spademoney.ledger.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relabels lapsed authorizations ACTIVE -> EXPIRED.
 *
 * This job is HOUSEKEEPING, not correctness. A lapsed hold already
 * stops reserving funds the moment it expires, because AccountBalances.held()
 * filters on `expires_at > now()` and capture's compare-and-set carries the same
 * predicate. If this sweeper never runs again, no balance is wrong and no money
 * is at risk -- the table just accumulates rows that say ACTIVE while behaving
 * as expired. Designing it the other way round, with correctness resting on a
 * scheduled job, means a paused job is a money bug.
 *
 * What it buys: EXPIRED becomes observable for reporting and reconciliation, and
 * the partial indexes on `status = 'ACTIVE'` stay small.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    /**
     * Bounded batch, and SKIP LOCKED so the sweeper never blocks a capture or a
     * void that is mid-flight on a row. Skipping is free precisely because this
     * job is not load-bearing: whatever it misses, it picks up next tick, and
     * the balance was right the whole time either way.
     */
    private static final String SWEEP_SQL = """
            UPDATE holds
               SET status = 'EXPIRED', resolved_at = now()
             WHERE id IN (
                   SELECT id
                     FROM holds
                    WHERE status = 'ACTIVE' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT ?
                      FOR UPDATE SKIP LOCKED
             )
            """;

    private final JdbcClient jdbcClient;
    private final int batchSize;

    public HoldExpirySweeper(JdbcClient jdbcClient,
            @Value("${spademoney.holds.sweeper.batch-size:500}") int batchSize) {
        this.jdbcClient = jdbcClient;
        this.batchSize = batchSize;
    }

    /** @return how many holds were relabelled. */
    @Transactional
    public int sweepExpiredHolds() {
        int expired = jdbcClient.sql(SWEEP_SQL).param(batchSize).update();
        if (expired > 0) {
            log.info("Expired {} lapsed hold(s)", expired);
        }
        return expired;
    }
}

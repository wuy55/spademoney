package com.spademoney.ledger.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains committed outbox rows to the broker.
 *
 * <h2>The delivery guarantee, stated exactly</h2>
 * At-least-once, never at-most-once. A row is marked published only after the
 * broker has acknowledged it, so a crash in the window between the send and the
 * mark republishes the same event -- carrying the same {@code event_id}, because
 * the id was minted at insert. Duplicates are therefore <em>expected</em>, and
 * the consumer's inbox turns them back into exactly-once effects. Marking first
 * and sending after would swap a visible duplicate for an invisible loss, which
 * is a far worse trade when the thing lost is a statement about money.
 *
 * <h2>Single-threaded and id-ordered, on purpose</h2>
 * The scan is {@code ORDER BY id ASC} and one tick runs on one thread, so events
 * leave in the order they were committed. Publishing concurrently would be
 * faster and would also let {@code HoldCaptured} overtake {@code HoldAuthorized}
 * for the same hold -- a consumer would then see a capture of a hold it has
 * never heard of. Ordering is bought with throughput here, deliberately; the
 * partition key preserves it on the broker side.
 *
 * <h2>A failed send stops the batch</h2>
 * The loop breaks on the first failure instead of skipping past it. Skipping is
 * the intuitive choice and it silently converts an ordered stream into an
 * unordered one at exactly the moment things are going wrong. Head-of-line
 * blocking is the correct trade: a stuck relay is loud, visible in the backlog,
 * and reported by the reconciliation job. An out-of-order stream is none of
 * those things.
 *
 * <h2>Why the whole tick is one transaction</h2>
 * {@code FOR UPDATE SKIP LOCKED} claims the rows for this tick, so a second
 * relay instance -- or a slow tick overlapping the next one -- picks up
 * different rows rather than double-publishing the same ones. It is a
 * duplicate-suppression convenience, not a correctness requirement: the inbox
 * would absorb those duplicates anyway. That is the right ordering of defences,
 * and it is why this class never needs a distributed lock.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String CLAIM_SQL = """
            SELECT id, event_id, aggregate_type, aggregate_id, event_type,
                   payload::text AS payload, occurred_at
              FROM outbox
             WHERE published_at IS NULL
             ORDER BY id ASC
             LIMIT ?
               FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE outbox
               SET published_at = now(), attempts = attempts + 1, last_error = NULL
             WHERE id = ?
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE outbox
               SET attempts = attempts + 1, last_error = ?
             WHERE id = ?
            """;

    private final JdbcClient jdbcClient;
    private final EventPublisher publisher;
    private final OutboxProperties properties;

    public OutboxRelay(JdbcClient jdbcClient, EventPublisher publisher, OutboxProperties properties) {
        this.jdbcClient = jdbcClient;
        this.publisher = publisher;
        this.properties = properties;
    }

    /** @return how many events this tick published and acknowledged. */
    @Transactional
    public int drainOnce() {
        List<OutboxRecord> batch = jdbcClient.sql(CLAIM_SQL)
                .param(properties.batchSize())
                .query(OutboxRelay::mapRecord)
                .list();

        int published = 0;
        for (OutboxRecord record : batch) {
            try {
                publisher.publish(record);
            } catch (RuntimeException e) {
                // Record why, then stop. Everything after this row keeps its
                // place in the queue; nothing overtakes it.
                jdbcClient.sql(MARK_FAILED_SQL)
                        .params(describe(e), record.id())
                        .update();
                log.warn("Outbox relay stopped at event {} ({}): {}",
                        record.eventId(), record.eventType(), describe(e));
                break;
            }
            jdbcClient.sql(MARK_PUBLISHED_SQL).param(record.id()).update();
            published++;
        }

        if (published > 0) {
            log.debug("Outbox relay published {} event(s)", published);
        }
        return published;
    }

    /** How far behind the relay is. Read by the reconciliation report. */
    public long backlog() {
        return jdbcClient.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    private static String describe(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static OutboxRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxRecord(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }
}

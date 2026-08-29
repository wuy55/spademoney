package com.spademoney.payments.inbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The idempotent consumer.
 *
 * <h2>The whole mechanism, in one transaction</h2>
 * <pre>
 *   BEGIN
 *     INSERT INTO inbox_events (event_id, ...) ON CONFLICT DO NOTHING
 *     if 0 rows inserted -> already seen, do nothing, COMMIT
 *     otherwise          -> run the handlers
 *   COMMIT
 * </pre>
 *
 * The dedupe record and the effect commit together, and that is the entire
 * argument. Take them apart in either direction and it breaks:
 *
 * <ul>
 *   <li>Effect first, then record: a crash in between redelivers an event that
 *       has already been applied, and it is applied a second time. Double
 *       effect.</li>
 *   <li>Record first, then effect: a crash in between leaves an event marked
 *       processed that never was. The redelivery is ignored and the effect is
 *       lost forever -- silently, which is worse.</li>
 * </ul>
 *
 * <h2>Why ON CONFLICT DO NOTHING rather than SELECT-then-INSERT</h2>
 * Two consumer threads handed the same redelivered event would both find no row
 * and both proceed. The unique index is what serializes them: one insert wins,
 * the other reports zero rows and returns. The check and the claim are the same
 * statement, so there is no gap between them to lose a race in. This is the
 * same shape as the Ledger's idempotency claim, for the same reason.
 *
 * <h2>What this does not promise</h2>
 * Not exactly-once <em>delivery</em> -- nobody can offer that across a network.
 * Exactly-once <em>effects</em>, given at-least-once delivery and a stable
 * event id. The delivery still happens twice; it is the second one that
 * changes nothing.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    private static final String CLAIM_SQL = """
            INSERT INTO inbox_events
                   (event_id, event_type, aggregate_type, aggregate_id, payload,
                    topic, partition, kafka_offset)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcClient jdbcClient;
    private final List<InboxEventHandler> handlers;

    public InboxService(JdbcClient jdbcClient, List<InboxEventHandler> handlers) {
        this.jdbcClient = jdbcClient;
        this.handlers = handlers;
    }

    /**
     * @return true if this delivery was the first one and the handlers ran;
     *         false if the event had already been processed.
     */
    @Transactional
    public boolean process(InboxEvent event) {
        int claimed = jdbcClient.sql(CLAIM_SQL)
                .params(event.eventId(), event.eventType(), event.aggregateType(),
                        event.aggregateId(), event.payload(),
                        event.topic(), event.partition(), event.offset())
                .update();

        if (claimed == 0) {
            // A duplicate is a normal, expected event, not a warning. The relay
            // is at-least-once on purpose and Kafka redelivers on rebalance;
            // logging this at WARN would train everyone to ignore warnings.
            log.debug("Ignoring already-processed event {} ({})", event.eventId(), event.eventType());
            return false;
        }

        for (InboxEventHandler handler : handlers) {
            if (handler.handles(event.eventType())) {
                handler.handle(event);
            }
        }
        return true;
    }
}

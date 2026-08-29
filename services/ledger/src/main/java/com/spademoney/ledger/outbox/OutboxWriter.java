package com.spademoney.ledger.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes a domain event into the outbox, in the caller's transaction.
 *
 * <h2>The whole point is the missing annotation</h2>
 * This class is deliberately <em>not</em> {@code @Transactional}, and that is
 * the entire mechanism rather than an oversight. {@code append} runs inside
 * whatever transaction the caller already opened -- the same one that inserted
 * the entries -- so the event and the money share a fate:
 *
 * <ul>
 *   <li>the transfer commits and the event is there to publish;</li>
 *   <li>the transfer rolls back and the event never existed.</li>
 * </ul>
 *
 * There is no third outcome, and no window between them, because there is only
 * one commit. Give this method {@code @Transactional(REQUIRES_NEW)} and the
 * guarantee is gone: the event would commit on its own and could describe a
 * transfer that then failed. Announcing money that did not move is worse than
 * announcing nothing.
 *
 * <h2>Why this is not just "publish to Kafka here"</h2>
 * Because Kafka is not in this transaction and cannot be. A broker send inside
 * a database transaction still has two commit points; it only moves the window,
 * it does not close it. The outbox does not make the broker transactional -- it
 * removes the broker from the write path, leaving one local transaction plus an
 * at-least-once relay, which is a problem the consumer can solve with a dedupe
 * key. See {@link OutboxRelay}.
 *
 * <h2>The event id is not minted here either</h2>
 * {@code event_id} is a column default ({@code gen_random_uuid()}), so it is
 * minted by the insert itself. This method reads it back rather than supplying
 * it, which keeps the invariant "one event, one id, forever" enforced by the
 * schema rather than by every call site remembering to do the right thing. That
 * matters because a republished event must carry the id it was born with, or
 * the consumer's dedupe matches nothing.
 */
@Component
public class OutboxWriter {

    public static final String AGGREGATE_TRANSACTION = "TRANSACTION";
    public static final String AGGREGATE_HOLD = "HOLD";

    private static final String APPEND_SQL = """
            INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?::jsonb)
            RETURNING event_id
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param aggregateType {@link #AGGREGATE_TRANSACTION} or {@link #AGGREGATE_HOLD}
     * @param aggregateId   becomes the broker partition key, so every event about
     *                      one hold or one transaction is consumed in order
     * @param payload       serialized now, not at publish time: an event is a
     *                      statement about a moment, and rebuilding it later from
     *                      state that has moved on produces a different statement
     * @return the id minted by the insert -- the consumer's dedupe key
     */
    public UUID append(String aggregateType, Object aggregateId, String eventType, Object payload) {
        return jdbcClient.sql(APPEND_SQL)
                .params(aggregateType, String.valueOf(aggregateId), eventType, serialize(payload))
                .query(UUID.class)
                .single();
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Not recoverable and not worth degrading: an event we cannot
            // serialize must fail the money movement it belongs to, because
            // committing the money without the event is the one outcome this
            // whole mechanism exists to prevent.
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType(payload), e);
        }
    }

    private static String eventType(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}

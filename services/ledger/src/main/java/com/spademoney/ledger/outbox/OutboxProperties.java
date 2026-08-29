package com.spademoney.ledger.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay tuning. The defaults are the compose defaults; the tests override the
 * interval and disable the trigger entirely.
 *
 * @param topic       the single topic every Ledger event is published to.
 *                    One topic, partitioned by aggregate, rather than a topic
 *                    per event type: a consumer that needs HoldAuthorized and
 *                    HoldCaptured in order must read them from one partition,
 *                    and separate topics have no ordering relationship at all.
 * @param batchSize   how many unpublished rows one tick may drain.
 * @param sendTimeout how long to wait for a broker acknowledgement before
 *                    treating the send as failed. Unbounded would let one
 *                    unreachable broker stall the relay thread forever, which
 *                    looks exactly like a relay that has nothing to do.
 */
@ConfigurationProperties("spademoney.outbox.relay")
public record OutboxProperties(
        String topic,
        int batchSize,
        Duration sendTimeout) {

    public OutboxProperties {
        topic = topic == null || topic.isBlank() ? "spademoney.ledger.events" : topic;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
    }
}

package com.spademoney.payments.inbox;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when consuming an event fails.
 *
 * <h2>Retry, then park — and why parking is not giving up</h2>
 * Kafka delivers in order within a partition, so a listener that keeps throwing
 * blocks every later event on that partition behind it. Retrying forever
 * therefore turns one bad record into an outage for every aggregate that hashes
 * to the same partition. The dead-letter topic is the escape: after a few
 * spaced-out attempts the record is published to
 * {@code spademoney.ledger.events.DLT} and the partition moves on.
 *
 * That trade is real and worth stating plainly. Parking a record means this
 * service has stopped tracking one aggregate's story, so the event is not
 * "handled" -- it is quarantined, and something has to look at it. That
 * something is the reconciliation job, which reports a non-empty dead-letter
 * topic as a finding rather than letting it sit there quietly.
 *
 * <h2>Exponential backoff, and what it is actually for</h2>
 * The failures worth retrying are the transient ones -- a database that is
 * momentarily unavailable, a lock timeout. Retrying those immediately is the
 * worst possible response: every consumer hammers the struggling dependency at
 * the exact moment it needs room. Backing off geometrically gives it that room.
 *
 * <h2>Some failures must not be retried at all</h2>
 * A record with no event-id header will still have no event-id header in eight
 * seconds. {@link UnprocessableEventException} is registered as non-retryable so
 * it is dead-lettered on the first attempt, rather than costing the partition
 * several seconds of head-of-line blocking to reach the same conclusion.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

    /**
     * @param kafkaOperations the producer used ONLY to write dead-lettered
     *                        records. Payments publishes nothing to the domain
     *                        stream -- facts flow Ledger to Payments, commands
     *                        flow the other way over HTTP -- and the DLT is
     *                        neither: it is this service's own record of what it
     *                        could not process.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                // Same partition number on the DLT as on the source topic, so a
                // dead-lettered record keeps its position relative to its
                // aggregate's other failures. The default sends to the same
                // partition index, which requires the DLT to have at least as
                // many partitions; being explicit documents the requirement.
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(8_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(UnprocessableEventException.class);
        return handler;
    }
}

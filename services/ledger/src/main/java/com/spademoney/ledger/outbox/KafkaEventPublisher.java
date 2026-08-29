package com.spademoney.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The real transport: one outbox row becomes one Kafka record.
 *
 * <h2>The send is made synchronous on purpose</h2>
 * {@code KafkaTemplate.send} is asynchronous, and using it that way here would
 * break the relay's only guarantee. The relay marks a row published after this
 * method returns; if the method returned before the broker had acknowledged,
 * the mark would record an intention, and a send that failed afterwards would
 * leave an event that was never delivered and never will be. Blocking on the
 * acknowledgement is what makes {@code published_at} a fact.
 *
 * <h2>Where the event id goes, and why in a header</h2>
 * The dedupe key travels as a Kafka header rather than only inside the JSON, so
 * a consumer can dedupe <em>before</em> parsing the body. That matters for the
 * poison-message case: a payload the consumer cannot deserialize still has to be
 * identifiable, or a redelivery of it cannot be recognised as the same message.
 * The header is metadata about the delivery; the body is the fact.
 */
@Component
class KafkaEventPublisher implements EventPublisher {

    static final String HEADER_EVENT_ID = "event-id";
    static final String HEADER_EVENT_TYPE = "event-type";
    static final String HEADER_AGGREGATE_TYPE = "aggregate-type";
    static final String HEADER_AGGREGATE_ID = "aggregate-id";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxRecord record) {
        ProducerRecord<String, String> message = new ProducerRecord<>(
                properties.topic(),
                // The partition key. Every event about one hold or one
                // transaction hashes to the same partition, which is the only
                // reason a multi-partition topic can still deliver that
                // aggregate's events in the order they were committed.
                record.partitionKey(),
                record.payload());

        header(message, HEADER_EVENT_ID, record.eventId().toString());
        header(message, HEADER_EVENT_TYPE, record.eventType());
        header(message, HEADER_AGGREGATE_TYPE, record.aggregateType());
        header(message, HEADER_AGGREGATE_ID, record.aggregateId());

        try {
            kafkaTemplate.send(message).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restore the flag before unwinding: swallowing it here would leave
            // a shutting-down relay thread looking healthy to everything above.
            Thread.currentThread().interrupt();
            throw new EventPublishFailedException(record, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishFailedException(record, e);
        }
    }

    private static void header(ProducerRecord<String, String> message, String name, String value) {
        message.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}

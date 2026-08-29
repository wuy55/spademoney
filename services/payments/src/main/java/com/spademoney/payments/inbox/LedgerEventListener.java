package com.spademoney.payments.inbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where the Ledger's event stream enters Payments.
 *
 * This class does as little as possible: pull the delivery metadata out of the
 * record, hand it to {@link InboxService}, and let anything thrown reach the
 * container's error handler. Everything that decides whether the event has an
 * effect lives in the inbox transaction, not here, because this method is not
 * transactional and a listener that half-applied an event before failing would
 * make the dedupe record a lie.
 *
 * <h2>Identity comes from the header, not the body</h2>
 * The event id is read before the payload is looked at. That ordering matters
 * for the poison case: a body this service cannot understand still has to be
 * identifiable, or a redelivery of it cannot be recognised as the same message
 * and the dead-letter topic fills with what look like distinct failures. The
 * header is metadata about the delivery; the body is the fact being delivered.
 *
 * <h2>Ordering</h2>
 * One listener thread per partition, and the Ledger keys every event on its
 * aggregate, so a hold's authorize always reaches this method before its
 * capture. Across aggregates there is no order and none is assumed.
 */
@Component
class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);

    static final String HEADER_EVENT_ID = "event-id";
    static final String HEADER_EVENT_TYPE = "event-type";
    static final String HEADER_AGGREGATE_TYPE = "aggregate-type";
    static final String HEADER_AGGREGATE_ID = "aggregate-id";

    private final InboxService inbox;

    LedgerEventListener(InboxService inbox) {
        this.inbox = inbox;
    }

    @KafkaListener(
            topics = "${spademoney.ledger.events-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    void onLedgerEvent(ConsumerRecord<String, String> record) {
        InboxEvent event = new InboxEvent(
                requiredUuid(record, HEADER_EVENT_ID),
                required(record, HEADER_EVENT_TYPE),
                required(record, HEADER_AGGREGATE_TYPE),
                required(record, HEADER_AGGREGATE_ID),
                record.value(),
                record.topic(),
                record.partition(),
                record.offset());

        boolean firstDelivery = inbox.process(event);
        if (firstDelivery) {
            log.info("Applied ledger event {} {} for {} {}",
                    event.eventType(), event.eventId(), event.aggregateType(), event.aggregateId());
        }
    }

    private static UUID requiredUuid(ConsumerRecord<String, String> record, String name) {
        String value = required(record, name);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableEventException(
                    "Header '" + name + "' is not a UUID: " + value, e);
        }
    }

    private static String required(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            // Nothing about waiting will make this message acceptable, so it is
            // marked non-retryable and goes straight to the dead-letter topic.
            // Retrying it would block the partition on a message that can never
            // succeed -- see KafkaConsumerConfig.
            throw new UnprocessableEventException(
                    "Record at " + record.topic() + "-" + record.partition() + "@" + record.offset()
                            + " has no '" + name + "' header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}

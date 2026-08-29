package com.spademoney.payments.inbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import com.spademoney.payments.TestcontainersConfiguration;
import com.spademoney.payments.ledger.LedgerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consumer against a real broker: headers survive the wire, duplicates are
 * absorbed, and a message that can never be processed gets out of the way
 * instead of blocking the partition behind it.
 *
 * {@link InboxDedupeTest} proves the dedupe logic; this proves the plumbing
 * around it, which is the part a unit test necessarily assumes.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true"
})
@Import({ TestcontainersConfiguration.class, RedpandaTestConfiguration.class })
class LedgerEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private LedgerProperties ledgerProperties;
    @Autowired
    private KafkaConnectionDetails kafkaConnectionDetails;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE inbox_events").update();
    }

    @Test
    void anEventPublishedByTheLedgerIsRecordedWithItsIdentityIntact() {
        UUID eventId = UUID.randomUUID();

        publish(eventId, "HoldAuthorized", "HOLD", "42", "{\"holdId\":42,\"amountMinor\":2500}");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inboxRowsFor(eventId)).isEqualTo(1));

        assertThat(jdbcClient.sql("SELECT event_type FROM inbox_events WHERE event_id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("HoldAuthorized");
        assertThat(jdbcClient.sql("SELECT aggregate_id FROM inbox_events WHERE event_id = ?")
                .param(eventId).query(String.class).single()).isEqualTo("42");
    }

    /**
     * The relay republishes after a crash between the broker ack and the
     * published_at update. Two identical records with one event id is exactly
     * what that looks like from here, so it is what the test sends.
     */
    @Test
    void aRepublishedEventLandsInTheInboxExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"holdId\":43}";

        publish(eventId, "HoldAuthorized", "HOLD", "43", payload);
        publish(eventId, "HoldAuthorized", "HOLD", "43", payload);
        publish(eventId, "HoldAuthorized", "HOLD", "43", payload);

        // A marker event behind them: once it has arrived, the three above have
        // certainly been consumed, because they share a partition key and Kafka
        // delivers a partition in order. Waiting on a marker rather than on a
        // sleep is what keeps this test deterministic.
        UUID marker = UUID.randomUUID();
        publish(marker, "HoldVoided", "HOLD", "43", "{\"holdId\":43}");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inboxRowsFor(marker)).isEqualTo(1));

        assertThat(inboxRowsFor(eventId)).isEqualTo(1);
    }

    /**
     * A record with no event id can never be processed: there is nothing to
     * dedupe on and no way to recognise its redelivery. Retrying it would block
     * every later event on that partition forever, so it is dead-lettered on the
     * first attempt -- and, crucially, the events behind it still arrive.
     */
    @Test
    void aRecordWithNoEventIdIsDeadLetteredAndDoesNotBlockThePartition() {
        ProducerRecord<String, String> poison =
                new ProducerRecord<>(ledgerProperties.eventsTopic(), "HOLD:99", "{\"holdId\":99}");
        poison.headers().add("event-type", "HoldAuthorized".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(poison);

        UUID healthy = UUID.randomUUID();
        publish(healthy, "HoldAuthorized", "HOLD", "99", "{\"holdId\":99}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(inboxRowsFor(healthy)).isEqualTo(1));

        List<ConsumerRecord<String, String>> parked = drain(ledgerProperties.deadLetterTopic());
        assertThat(parked).isNotEmpty();
        assertThat(parked.getFirst().value()).contains("\"holdId\":99");
    }

    private void publish(UUID eventId, String eventType, String aggregateType,
            String aggregateId, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                ledgerProperties.eventsTopic(), aggregateType + ":" + aggregateId, payload);
        header(record, "event-id", eventId.toString());
        header(record, "event-type", eventType);
        header(record, "aggregate-type", aggregateType);
        header(record, "aggregate-id", aggregateId);
        kafkaTemplate.send(record);
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private long inboxRowsFor(UUID eventId) {
        return jdbcClient.sql("SELECT count(*) FROM inbox_events WHERE event_id = ?")
                .param(eventId).query(Long.class).single();
    }

    private List<ConsumerRecord<String, String>> drain(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnectionDetails.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            for (int attempt = 0; attempt < 15 && all.isEmpty(); attempt++) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(2));
                batch.forEach(all::add);
            }
        }
        return all;
    }
}

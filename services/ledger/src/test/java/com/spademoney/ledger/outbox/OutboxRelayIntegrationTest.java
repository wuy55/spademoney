package com.spademoney.ledger.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real broker.
 *
 * {@link OutboxRelayTest} proves the relay's decisions; this proves the
 * transport actually carries them — the topic exists, the record lands on it,
 * the dedupe key survives as a header, and the partition key is what the design
 * says it is. Those are exactly the things a recording publisher cannot tell
 * you, because it is the thing being trusted.
 *
 * One test class, one container. Everything else in the module stays broker-free.
 */
// Topic auto-creation is switched back on for this class alone. It is off for
// the rest of the suite (see src/test/resources/application.yml), because
// KafkaAdmin logs a stack trace on every context start when there is no broker
// to talk to -- 130 tests' worth of noise announcing something that is not a
// problem. Here there IS a broker, and the declared three-partition topic is
// part of what this test exists to exercise.
@SpringBootTest(properties = "spring.kafka.admin.auto-create=true")
@Import({ TestcontainersConfiguration.class, RedpandaTestConfiguration.class })
class OutboxRelayIntegrationTest {

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private LedgerTransactionService ledger;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private OutboxProperties outboxProperties;
    /**
     * The container's address, not the one in application.yml.
     *
     * {@code @ServiceConnection} contributes a {@link KafkaConnectionDetails}
     * bean and the auto-configuration reads the broker address from it;
     * {@code KafkaProperties} still holds the static localhost value from
     * configuration. Building this test's consumer from KafkaProperties would
     * therefore point it at a broker that is not the one the relay just
     * published to — and the test would fail with an empty topic while
     * everything was in fact working.
     */
    @Autowired
    private KafkaConnectionDetails kafkaConnectionDetails;

    @Test
    void aPostedTransferReachesTheBrokerWithItsEventIdAndPartitionKeyIntact() {
        jdbcClient.sql("""
                TRUNCATE outbox, holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        Long cash = account("CASH");
        Long payer = account("USER_WALLET");
        Long payee = account("USER_WALLET");
        fund(cash, payer, 50_000L);

        Long transactionId = ledger.transfer(payer, payee, Money.of(2_500L, Currency.getInstance("USD")));
        UUID eventId = jdbcClient.sql("SELECT event_id FROM outbox ORDER BY id DESC LIMIT 1")
                .query(UUID.class).single();

        assertThat(relay.drainOnce()).isEqualTo(1);

        List<ConsumerRecord<String, String>> received = consumeAll();
        assertThat(received).hasSize(1);

        ConsumerRecord<String, String> record = received.getFirst();
        assertThat(record.key()).isEqualTo("TRANSACTION:" + transactionId);
        assertThat(header(record, KafkaEventPublisher.HEADER_EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(header(record, KafkaEventPublisher.HEADER_EVENT_TYPE))
                .isEqualTo(LedgerEvents.TRANSFER_POSTED);
        assertThat(header(record, KafkaEventPublisher.HEADER_AGGREGATE_TYPE))
                .isEqualTo(OutboxWriter.AGGREGATE_TRANSACTION);
        assertThat(EventPayloads.longField(record.value(), "transactionId")).isEqualTo(transactionId);
        assertThat(EventPayloads.longField(record.value(), "amountMinor")).isEqualTo(2_500L);

        // And the row is now marked, so a second tick sends nothing.
        assertThat(relay.drainOnce()).isZero();
    }

    /**
     * Reads the topic from the beginning with a throwaway group id, so the test
     * sees everything the relay published regardless of what ran before it.
     */
    private List<ConsumerRecord<String, String>> consumeAll() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnectionDetails.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(outboxProperties.topic()));
            // Poll a few times: the first poll usually only completes the group
            // join, and an empty result there means "not yet", not "nothing".
            for (int attempt = 0; attempt < 10 && all.isEmpty(); attempt++) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(2));
                batch.forEach(all::add);
            }
        }
        return all;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long cash, Long accountId, long amountMinor) {
        Long txn = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, 'CREDIT', ?, 'USD'),
                       (?, ?, 'DEBIT', ?, 'USD')
                """)
                .params(txn, accountId, amountMinor, txn, cash, amountMinor)
                .update();
    }
}

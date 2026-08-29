package com.spademoney.ledger.outbox;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay's contract: ordered, at-least-once, and never at-most-once.
 *
 * The broker is a {@link RecordingEventPublisher} here rather than a real
 * Redpanda, because the behaviour under test is what the relay does when a send
 * <em>fails</em>, and a real broker cannot be asked to refuse the third message
 * and accept the fourth. The real transport is proven separately, against a real
 * Redpanda, in {@link OutboxRelayIntegrationTest}.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, OutboxRelayTest.RecordingPublisherConfig.class })
class OutboxRelayTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPublisherConfig {
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private RecordingEventPublisher publisher;
    @Autowired
    private LedgerTransactionService ledger;
    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE outbox, holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        publisher.reset();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        fund(payer, 1_000_000L);
    }

    @Test
    void drainingPublishesEveryUnpublishedEventAndMarksIt() {
        transfer(1_000L);
        transfer(2_000L);

        assertThat(relay.drainOnce()).isEqualTo(2);

        assertThat(publisher.published()).hasSize(2);
        assertThat(unpublishedCount()).isZero();
    }

    /**
     * Publication order is insert order, which is commit order.
     *
     * Without it, a consumer can see a hold captured before it was authorized.
     * The relay buys this with throughput -- one thread, one event at a time --
     * and that trade is the design, not a limitation waiting to be optimised
     * away.
     */
    @Test
    void eventsLeaveInTheOrderTheyWereCommitted() {
        transfer(1_000L);
        transfer(2_000L);
        transfer(3_000L);

        relay.drainOnce();

        assertThat(publisher.published())
                .extracting(OutboxRecord::id)
                .isSorted();
        assertThat(publisher.published())
                .extracting(record -> amountIn(record.payload()))
                .containsExactly(1_000L, 2_000L, 3_000L);
    }

    /**
     * A drained event is never re-sent on the next tick. This is the ordinary
     * case; the interesting case is the one below it.
     */
    @Test
    void aSecondDrainWithNothingNewPublishesNothing() {
        transfer(1_000L);
        relay.drainOnce();

        assertThat(relay.drainOnce()).isZero();
        assertThat(publisher.published()).hasSize(1);
    }

    /**
     * The head-of-line rule.
     *
     * When the broker refuses event two, events three and four must stay where
     * they are. Skipping the failure and carrying on is the intuitive choice and
     * it silently turns an ordered stream into an unordered one at exactly the
     * moment the system is already unhealthy. A stuck relay is loud and shows up
     * in the backlog; a reordered stream shows up as a support ticket.
     */
    @Test
    void aFailedSendStopsTheBatchInsteadOfSkippingPastIt() {
        transfer(1_000L);
        transfer(2_000L);
        transfer(3_000L);

        publisher.failOn(record -> amountIn(record.payload()) == 2_000L);

        assertThat(relay.drainOnce()).isEqualTo(1);

        assertThat(publisher.published())
                .extracting(record -> amountIn(record.payload()))
                .containsExactly(1_000L);
        // Two and three are both still queued -- three because it is behind two,
        // not because anything is wrong with it.
        assertThat(unpublishedCount()).isEqualTo(3 - 1);
        assertThat(relay.backlog()).isEqualTo(2);
    }

    @Test
    void aFailedSendIsRecordedAgainstTheRowSoTheReasonSurvivesTheTick() {
        transfer(1_000L);
        publisher.failOn(record -> true);

        relay.drainOnce();

        assertThat(lastErrorOfFirstRow()).contains("broker refused");
        assertThat(attemptsOfFirstRow()).isEqualTo(1);
        assertThat(publishedAtOfFirstRow()).isNull();
    }

    /**
     * At-least-once, with a stable identity.
     *
     * A failed send is retried, and the retry carries the SAME event id -- the
     * one minted when the event was written. That is the property the consumer's
     * dedupe depends on: a redelivery has to be recognisable as the same event,
     * and it can only be if nothing along the path is allowed to mint a new id.
     */
    @Test
    void aRetryAfterAFailedSendCarriesTheSameEventId() {
        transfer(1_000L);
        UUID idInTheOutbox = firstEventId();

        publisher.failOn(record -> true);
        relay.drainOnce();
        assertThat(publisher.published()).isEmpty();

        publisher.succeedAlways();
        assertThat(relay.drainOnce()).isEqualTo(1);

        assertThat(publisher.published()).hasSize(1);
        assertThat(publisher.published().getFirst().eventId()).isEqualTo(idInTheOutbox);
        assertThat(firstEventId()).isEqualTo(idInTheOutbox);
    }

    @Test
    void thePartitionKeyIsTypeQualifiedSoAHoldAndATransactionSharingAnIdDoNotCollide() {
        transfer(1_000L);
        relay.drainOnce();

        OutboxRecord record = publisher.published().getFirst();
        assertThat(record.partitionKey()).isEqualTo("TRANSACTION:" + record.aggregateId());
    }

    private void transfer(long amountMinor) {
        ledger.transfer(payer, payee, Money.of(amountMinor, Currency.getInstance("USD")));
    }

    private static long amountIn(String payload) {
        return EventPayloads.longField(payload, "amountMinor");
    }

    private long unpublishedCount() {
        return jdbcClient.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class).single();
    }

    private UUID firstEventId() {
        return jdbcClient.sql("SELECT event_id FROM outbox ORDER BY id ASC LIMIT 1")
                .query(UUID.class).single();
    }

    private String lastErrorOfFirstRow() {
        return jdbcClient.sql("SELECT last_error FROM outbox ORDER BY id ASC LIMIT 1")
                .query(String.class).single();
    }

    private int attemptsOfFirstRow() {
        return jdbcClient.sql("SELECT attempts FROM outbox ORDER BY id ASC LIMIT 1")
                .query(Integer.class).single();
    }

    private java.time.OffsetDateTime publishedAtOfFirstRow() {
        List<java.time.OffsetDateTime> rows = jdbcClient
                .sql("SELECT published_at FROM outbox ORDER BY id ASC LIMIT 1")
                .query((rs, n) -> rs.getObject("published_at", java.time.OffsetDateTime.class))
                .list();
        return rows.getFirst();
    }

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long accountId, long amountMinor) {
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

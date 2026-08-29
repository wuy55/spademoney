package com.spademoney.payments.inbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.payments.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exactly-once-effects claim, tested without a broker.
 *
 * The delivery is simulated because the delivery is not what is being proven --
 * Kafka's at-least-once behaviour is a given, not a hypothesis. What is being
 * proven is that a second delivery of the same event changes nothing, and that
 * a handler failure leaves the event genuinely unprocessed rather than merely
 * marked as such.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, InboxDedupeTest.CountingHandlerConfig.class })
class InboxDedupeTest {

    /** Stands in for the saga confirmation handler that arrives with the saga. */
    static class CountingHandler implements InboxEventHandler {
        final List<UUID> seen = new ArrayList<>();
        boolean explode;

        @Override
        public boolean handles(String eventType) {
            return "HoldAuthorized".equals(eventType);
        }

        @Override
        public void handle(InboxEvent event) {
            if (explode) {
                throw new IllegalStateException("handler failed");
            }
            seen.add(event.eventId());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingHandlerConfig {
        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }

    @Autowired
    private InboxService inbox;
    @Autowired
    private CountingHandler handler;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE inbox_events").update();
        handler.seen.clear();
        handler.explode = false;
    }

    @Test
    void aFirstDeliveryIsRecordedAndHandled() {
        InboxEvent event = event(UUID.randomUUID(), "HoldAuthorized");

        assertThat(inbox.process(event)).isTrue();

        assertThat(handler.seen).containsExactly(event.eventId());
        assertThat(rowCount()).isEqualTo(1);
    }

    /**
     * The one that matters. The relay is at-least-once and Kafka redelivers on
     * every rebalance, so this is not a rare path -- it is the normal one on a
     * bad day.
     */
    @Test
    void aRedeliveredEventIsRecognisedAndHasNoSecondEffect() {
        InboxEvent event = event(UUID.randomUUID(), "HoldAuthorized");

        assertThat(inbox.process(event)).isTrue();
        assertThat(inbox.process(event)).isFalse();
        assertThat(inbox.process(event)).isFalse();

        assertThat(handler.seen).hasSize(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    /**
     * Identity is the event id, nothing else. The same event redelivered on a
     * different partition or offset after a rebalance must still be recognised,
     * which it would not be if position were part of the key.
     */
    @Test
    void redeliveryIsRecognisedEvenWhenTheBrokerCoordinatesDiffer() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.process(new InboxEvent(eventId, "HoldAuthorized", "HOLD", "7",
                "{\"holdId\":7}", "spademoney.ledger.events", 0, 41L))).isTrue();
        assertThat(inbox.process(new InboxEvent(eventId, "HoldAuthorized", "HOLD", "7",
                "{\"holdId\":7}", "spademoney.ledger.events", 2, 900L))).isFalse();

        assertThat(handler.seen).hasSize(1);
    }

    @Test
    void distinctEventsAreEachHandledOnce() {
        inbox.process(event(UUID.randomUUID(), "HoldAuthorized"));
        inbox.process(event(UUID.randomUUID(), "HoldAuthorized"));

        assertThat(handler.seen).hasSize(2);
        assertThat(rowCount()).isEqualTo(2);
    }

    /**
     * The event's effect and its dedupe record share a transaction, so a
     * handler that throws must leave NO trace -- otherwise the redelivery would
     * be skipped and the effect lost forever, which is the failure mode nobody
     * notices until reconciliation.
     */
    @Test
    void aFailedHandlerRollsBackTheDedupeRecordSoRedeliveryStillWorks() {
        InboxEvent event = event(UUID.randomUUID(), "HoldAuthorized");
        handler.explode = true;

        assertThatThrownBy(() -> inbox.process(event)).isInstanceOf(IllegalStateException.class);
        assertThat(rowCount()).isZero();

        handler.explode = false;
        assertThat(inbox.process(event)).isTrue();
        assertThat(handler.seen).containsExactly(event.eventId());
    }

    /**
     * An event nobody is interested in is still recorded. The inbox is Payments'
     * only local account of what the Ledger did -- it cannot read the Ledger's
     * database -- so discarding unhandled events would leave reconciliation
     * comparing saga state against a story with pages missing.
     */
    @Test
    void anEventWithNoInterestedHandlerIsStillRecorded() {
        assertThat(inbox.process(event(UUID.randomUUID(), "RefundPosted"))).isTrue();

        assertThat(handler.seen).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
    }

    private static InboxEvent event(UUID eventId, String type) {
        return new InboxEvent(eventId, type, "HOLD", "7", "{\"holdId\":7}",
                "spademoney.ledger.events", 1, 12L);
    }

    private long rowCount() {
        return jdbcClient.sql("SELECT count(*) FROM inbox_events").query(Long.class).single();
    }
}

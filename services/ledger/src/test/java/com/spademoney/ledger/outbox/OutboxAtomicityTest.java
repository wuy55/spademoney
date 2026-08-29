package com.spademoney.ledger.outbox;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.hold.AuthorizeRequest;
import com.spademoney.ledger.hold.HoldResponse;
import com.spademoney.ledger.hold.HoldService;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim the whole outbox pattern rests on, tested directly:
 *
 * <blockquote>the event exists if and only if the money moved.</blockquote>
 *
 * Both halves matter and the second half is the one that is easy to ship
 * broken. A "publish after the transaction" implementation passes every
 * happy-path test ever written and fails only the rejection case -- announcing
 * a transfer that was refused -- which is why there is a test here for a
 * transfer that must NOT produce an event.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxAtomicityTest {

    @Autowired
    private LedgerTransactionService ledger;
    @Autowired
    private HoldService holds;
    @Autowired
    private JdbcClient jdbcClient;

    private record OutboxRow(UUID eventId, String aggregateType, String aggregateId,
            String eventType, String payload, java.time.OffsetDateTime publishedAt) {
    }

    private Long cash;
    private Long payer;
    private Long payee;
    private long transactionsAfterSetup;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE outbox, holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        // Funded with raw inserts rather than through the ledger service, so the
        // setup contributes no events and each test counts only what it caused.
        fund(payer, 100_000L);
        transactionsAfterSetup = transactionCount();
    }

    @Test
    void aPostedTransferLeavesExactlyOneUnpublishedEventDescribingIt() {
        Long transactionId = ledger.transfer(payer, payee, Money.of(2_500L, Currency.getInstance("USD")));

        List<OutboxRow> rows = outbox();
        assertThat(rows).hasSize(1);

        OutboxRow row = rows.getFirst();
        assertThat(row.eventType()).isEqualTo(LedgerEvents.TRANSFER_POSTED);
        assertThat(row.aggregateType()).isEqualTo(OutboxWriter.AGGREGATE_TRANSACTION);
        assertThat(row.aggregateId()).isEqualTo(String.valueOf(transactionId));
        // Not yet sent: the relay has not run. This is the state the outbox is
        // supposed to be in between the commit and the delivery.
        assertThat(row.publishedAt()).isNull();
        assertThat(EventPayloads.longField(row.payload(), "transactionId")).isEqualTo(transactionId);
        assertThat(EventPayloads.longField(row.payload(), "fromAccountId")).isEqualTo(payer);
        assertThat(EventPayloads.longField(row.payload(), "toAccountId")).isEqualTo(payee);
        assertThat(EventPayloads.longField(row.payload(), "amountMinor")).isEqualTo(2_500L);
        assertThat(EventPayloads.stringField(row.payload(), "currency")).isEqualTo("USD");
    }

    /**
     * The half that a publish-after-commit implementation gets wrong.
     *
     * The transfer is refused inside the transaction, so the transaction rolls
     * back -- and the outbox row, having been written in that same transaction,
     * goes with it. Nothing downstream ever hears about a transfer that did not
     * happen. There is no compensating "actually, ignore that" event, because
     * there is nothing to compensate.
     */
    @Test
    void aRefusedTransferLeavesNoEventAtAll() {
        assertThatThrownBy(() -> ledger.transfer(payer, payee, Money.of(999_999L, Currency.getInstance("USD"))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(outbox()).isEmpty();
        // And no transaction row either: the whole thing rolled back together.
        // The baseline is the funding transaction written by setUp, so the
        // assertion is "nothing was added", not "the table is empty".
        assertThat(transactionCount()).isEqualTo(transactionsAfterSetup);
    }

    @Test
    void anAuthorizationIsAnnouncedAndKeyedOnTheHold() {
        HoldResponse hold = holds.authorize(new AuthorizeRequest(payer, payee, 30_000L, "USD", 3600L));

        List<OutboxRow> rows = outbox();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().eventType()).isEqualTo(LedgerEvents.HOLD_AUTHORIZED);
        assertThat(rows.getFirst().aggregateType()).isEqualTo(OutboxWriter.AGGREGATE_HOLD);
        assertThat(rows.getFirst().aggregateId()).isEqualTo(String.valueOf(hold.holdId()));
    }

    /**
     * Authorize then capture: two events, one aggregate, in that order.
     *
     * The shared aggregate id is what puts them on one broker partition, and the
     * partition is the only place Kafka promises order. A consumer that sees
     * HoldCaptured for a hold it has never heard of has to either buffer or
     * guess, and both of those are bugs waiting for a busy day.
     */
    @Test
    void aHoldsEventsShareOneAggregateIdSoTheyCannotArriveOutOfOrder() {
        HoldResponse hold = holds.authorize(new AuthorizeRequest(payer, payee, 30_000L, "USD", 3600L));
        holds.capture(hold.holdId(), 20_000L);

        List<OutboxRow> rows = outbox();
        assertThat(rows).extracting(OutboxRow::eventType)
                .containsExactly(LedgerEvents.HOLD_AUTHORIZED, LedgerEvents.HOLD_CAPTURED);
        assertThat(rows).extracting(OutboxRow::aggregateId)
                .containsOnly(String.valueOf(hold.holdId()));
        assertThat(EventPayloads.longField(rows.getLast().payload(), "capturedMinor")).isEqualTo(20_000L);
        assertThat(EventPayloads.longField(rows.getLast().payload(), "releasedMinor")).isEqualTo(10_000L);
    }

    @Test
    void aVoidIsAnnouncedSoASagaWaitingOnTheHoldLearnsItWasReleased() {
        HoldResponse hold = holds.authorize(new AuthorizeRequest(payer, payee, 30_000L, "USD", 3600L));
        holds.voidHold(hold.holdId());

        assertThat(outbox()).extracting(OutboxRow::eventType)
                .containsExactly(LedgerEvents.HOLD_AUTHORIZED, LedgerEvents.HOLD_VOIDED);
    }

    /**
     * Two events, two ids, and both minted by the database.
     *
     * This is not a test of {@code gen_random_uuid()}. It is a test that no Java
     * code supplies the id, which is what stops a relay republishing a crashed
     * event under a fresh id -- the failure that quietly defeats every
     * downstream dedupe.
     */
    @Test
    void everyEventGetsItsOwnIdMintedByTheInsert() {
        ledger.transfer(payer, payee, Money.of(1_000L, Currency.getInstance("USD")));
        ledger.transfer(payer, payee, Money.of(1_000L, Currency.getInstance("USD")));

        List<UUID> ids = outbox().stream().map(OutboxRow::eventId).toList();
        assertThat(ids).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
    }

    private List<OutboxRow> outbox() {
        return jdbcClient.sql("""
                SELECT event_id, aggregate_type, aggregate_id, event_type,
                       payload::text AS payload, published_at
                  FROM outbox ORDER BY id ASC
                """)
                .query((rs, n) -> new OutboxRow(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("published_at", java.time.OffsetDateTime.class)))
                .list();
    }

    private long transactionCount() {
        return jdbcClient.sql("SELECT count(*) FROM transactions").query(Long.class).single();
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

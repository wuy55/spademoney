package com.spademoney.ledger.refund;

import java.util.Currency;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.query.AccountBalances;
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.service.LedgerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refunds are reversing entries, never mutations. The original transaction is
 * immutable; a refund is a new posting that names what it undoes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefundTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private RefundService refunds;
    @Autowired
    private LedgerTransactionService ledger;
    @Autowired
    private AccountBalances balances;
    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        fund(payer, 100_000L);
    }

    @Test
    void aFullRefundReturnsTheMoneyAndLeavesTheOriginalUntouched() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        assertThat(balances.posted(payer)).isEqualTo(70_000L);

        RefundResponse refund = refunds.refund(new RefundRequest(original, 30_000L));

        assertThat(refund.amountMinor()).isEqualTo(30_000L);
        assertThat(refund.totalRefundedMinor()).isEqualTo(30_000L);
        assertThat(refund.remainingRefundableMinor()).isZero();

        assertThat(balances.posted(payer)).isEqualTo(100_000L);
        assertThat(balances.posted(payee)).isZero();

        // The original's entries are untouched: the refund is a SEPARATE posting.
        assertThat(entryCountFor(original)).isEqualTo(2);
        assertThat(entryCountFor(refund.refundTransactionId())).isEqualTo(2);
    }

    @Test
    void aRefundIsRecordedAsAReversalNamingItsOriginal() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        RefundResponse refund = refunds.refund(new RefundRequest(original, 30_000L));

        record Row(String type, Long reverses) {
        }
        Row row = jdbcClient.sql("SELECT type, reverses_transaction_id FROM transactions WHERE id = ?")
                .param(refund.refundTransactionId())
                .query((rs, n) -> new Row(rs.getString("type"), rs.getObject("reverses_transaction_id", Long.class)))
                .single();

        assertThat(row.type()).isEqualTo("REFUND");
        assertThat(row.reverses()).isEqualTo(original);
    }

    @Test
    void aRefundRunsTheOriginalDirectionsBackwards() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        RefundResponse refund = refunds.refund(new RefundRequest(original, 30_000L));

        String refundDebitAccount = jdbcClient.sql("""
                SELECT account_id::text FROM entries
                 WHERE transaction_id = ? AND direction = 'DEBIT'
                """).param(refund.refundTransactionId()).query(String.class).single();

        // The original debited the payer; the refund debits whoever was PAID.
        assertThat(refundDebitAccount).isEqualTo(String.valueOf(payee));
    }

    @Test
    void partialRefundsAccumulateUpToTheOriginalAmount() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));

        RefundResponse first = refunds.refund(new RefundRequest(original, 10_000L));
        assertThat(first.totalRefundedMinor()).isEqualTo(10_000L);
        assertThat(first.remainingRefundableMinor()).isEqualTo(20_000L);

        RefundResponse second = refunds.refund(new RefundRequest(original, 20_000L));
        assertThat(second.totalRefundedMinor()).isEqualTo(30_000L);
        assertThat(second.remainingRefundableMinor()).isZero();

        assertThat(balances.posted(payer)).isEqualTo(100_000L);
        assertThat(balances.posted(payee)).isZero();
    }

    @Test
    void refundingMoreThanWasPostedIsRejected() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));

        assertThatThrownBy(() -> refunds.refund(new RefundRequest(original, 30_001L)))
                .isInstanceOf(RefundExceedsOriginalException.class);

        assertThat(balances.posted(payee)).as("nothing was given back").isEqualTo(30_000L);
    }

    @Test
    void refundsCannotSumPastTheOriginalAmount() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        refunds.refund(new RefundRequest(original, 25_000L));

        assertThatThrownBy(() -> refunds.refund(new RefundRequest(original, 10_000L)))
                .isInstanceOf(RefundExceedsOriginalException.class);

        assertThat(balances.posted(payee)).isEqualTo(5_000L);
    }

    // Undoing an undo is a new payment, not a refund.
    @Test
    void aRefundCannotItselfBeRefunded() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        RefundResponse refund = refunds.refund(new RefundRequest(original, 30_000L));

        assertThatThrownBy(() -> refunds.refund(new RefundRequest(refund.refundTransactionId(), 1_000L)))
                .isInstanceOf(UnrefundableTransactionException.class);
    }

    @Test
    void refundingAnUnknownTransactionIsNotFound() {
        assertThatThrownBy(() -> refunds.refund(new RefundRequest(999_999L, 1_000L)))
                .isInstanceOf(RefundTargetNotFoundException.class);
    }

    // The chosen policy: no account goes negative, anywhere. A merchant who has
    // already moved the money out cannot be refunded from an empty balance.
    @Test
    void refundingAPayeeWhoAlreadySpentTheMoneyFailsRatherThanGoingNegative() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        ledger.transfer(payee, payer, Money.of(30_000L, USD)); // merchant moves it out
        assertThat(balances.posted(payee)).isZero();

        assertThatThrownBy(() -> refunds.refund(new RefundRequest(original, 30_000L)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balances.posted(payee)).isNotNegative();
    }

    // Both rules reject this refund at once: it exceeds the remaining cap AND
    // the merchant cannot cover it. The cap is reported, because no amount of
    // waiting makes it succeed, while an empty balance can be topped up. Pins
    // the order of the two checks, which is otherwise only implied by the order
    // of two statements.
    @Test
    void whenBothTheCapAndTheBalanceRejectItTheCapIsReported() {
        Long original = ledger.transfer(payer, payee, Money.of(30_000L, USD));
        refunds.refund(new RefundRequest(original, 25_000L));   // 5_000 still refundable
        ledger.transfer(payee, payer, Money.of(5_000L, USD));   // merchant spends the rest

        assertThat(balances.available(payee)).isZero();

        assertThatThrownBy(() -> refunds.refund(new RefundRequest(original, 10_000L)))
                .isInstanceOf(RefundExceedsOriginalException.class);
    }

    // A capture is refundable like any other posting -- that is the point of
    // recording its type rather than leaving it indistinguishable from a transfer.
    @Test
    void aCaptureCanBeRefunded() {
        Long capture = ledger.createTransaction("CAPTURE", null);
        ledger.postDoubleEntry(capture, payer, payee, Money.of(20_000L, USD));

        RefundResponse refund = refunds.refund(new RefundRequest(capture, 20_000L));

        assertThat(refund.totalRefundedMinor()).isEqualTo(20_000L);
        assertThat(balances.posted(payee)).isZero();
    }

    // Concurrency, independent oracle: a 30_000 transfer, 10 threads each trying
    // to refund 12_000. Without the account locks serializing the cap check, all
    // ten would read "0 refunded so far" and hand back 120_000 against a 30_000
    // original. With correct serialization exactly floor(30_000/12_000) = 2
    // succeed. The expected value is arithmetic, not read back from the code.
    @Test
    void concurrentRefundsCannotSumPastTheOriginal() throws InterruptedException {
        final long AMOUNT = 12_000L;
        final long ORIGINAL = 30_000L;
        final int THREADS = 10;
        final int EXPECTED = (int) (ORIGINAL / AMOUNT);

        Long original = ledger.transfer(payer, payee, Money.of(ORIGINAL, USD));
        // Fund the merchant far beyond the original so the CAP is the only
        // binding constraint. Left alone, the payee holds exactly ORIGINAL, and
        // the balance would independently allow the same floor(30000/12000) = 2
        // refunds -- so deleting the cap check entirely would still produce 2
        // and this test would pass while proving nothing.
        fund(payee, 500_000L);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger successes = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    refunds.refund(new RefundRequest(original, AMOUNT));
                    successes.incrementAndGet();
                } catch (RefundExceedsOriginalException expected) {
                    // legal once the refundable remainder is exhausted
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        startGate.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("threads hung -- possible deadlock").isTrue();
        assertThat(unexpected).as("only cap-exceeded is a legal failure").isEmpty();
        assertThat(successes.get()).isEqualTo(EXPECTED);

        long refunded = jdbcClient.sql("""
                SELECT COALESCE(SUM(e.amount_minor), 0) FROM transactions t
                  JOIN entries e ON e.transaction_id = t.id
                 WHERE t.reverses_transaction_id = ? AND t.type = 'REFUND' AND e.direction = 'DEBIT'
                """).param(original).query(Long.class).single();

        assertThat(refunded).as("refunds may never sum past the original").isLessThanOrEqualTo(ORIGINAL);
        assertThat(refunded).isEqualTo(EXPECTED * AMOUNT);
        assertThat(globalSignedSum()).as("global ledger still nets to zero").isZero();
    }

    // ---------- helpers ----------

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long walletId, long amountMinor) {
        Long txnId = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """).params(txnId, cash, amountMinor, txnId, walletId, amountMinor).update();
    }

    private int entryCountFor(Long transactionId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM entries WHERE transaction_id = ?")
                .param(transactionId).query(Integer.class).single();
    }

    private long globalSignedSum() {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                FROM entries
                """).query(Long.class).single();
    }
}

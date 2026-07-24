package com.spademoney.ledger.service;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.spademoney.ledger.TestcontainersConfiguration;
import com.spademoney.ledger.money.Money;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.RandomGenerator;

import static org.assertj.core.api.Assertions.*;

/**
 * Property test for ANY random sequence of
 * transfer attempts, the global ledger must stay internally consistent.
 *
 * Two invariants are asserted after EVERY op:
 * (1) Global zero-sum — Σ over the WHOLE entries table of
 * (CREDIT:+amount, DEBIT:-amount) == 0. Holds because every posting
 * (funding AND transfer) is balanced by construction; the DB deferred
 * trigger enforces it per transaction, this proves it globally.
 * (2) No USER_WALLET ever goes negative — the ordered FOR UPDATE lock +
 * atomic overdraft check must hold under random interleavings.
 *
 * Note the CASH account is deliberately NOT asserted non-negative: it is the
 * system account money is issued *from* (a deposit debits CASH, credits the
 * wallet — wallets are a liability), so it runs negative by the total funded.
 * The global zero-sum still holds.
 *
 * This runs as @SpringBootTest/@Test rather than jqwik @Property so the
 * service keeps its real, proxy-driven @Transactional boundary (a hand-built
 * bean would make @Transactional a no-op and quietly break atomicity). jqwik
 * still drives generation; the fixed seed makes any failure reproducible. The
 * tradeoff vs a native @Property is loss of automatic shrinking.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerZeroSumPropertyTest {

    @Autowired
    private LedgerTransactionService ledgerService;

    @Autowired
    private JdbcClient jdbcClient;

    private static final Currency USD = Currency.getInstance("USD");
    private static final int POOL_SIZE = 4;
    private static final long INITIAL_FUNDING = 100_000L;
    private static final int NUM_SEQUENCES = 20;
    private static final int SEQUENCE_LENGTH = 25;
    private static final long MAX_TRANSFER = 200_000L; // > funding → forces overdraft attempts

    private record TransferOp(int fromIdx, int toIdx, long amountMinor) {
    }

    @Test
    void globalLedgerNetsToZeroUnderRandomTransferSequences() {
        // fromIdx != toIdx so every op is a genuine two-account transfer.
        Arbitrary<TransferOp> opArb = Combinators.combine(
                Arbitraries.integers().between(0, POOL_SIZE - 1),
                Arbitraries.integers().between(0, POOL_SIZE - 1),
                Arbitraries.longs().between(1L, MAX_TRANSFER))
                .as((from, to, amount) -> new TransferOp(from, to, amount))
                .filter(op -> op.fromIdx() != op.toIdx());

        // Seeded for reproducibility: a failing run replays identically.
        Random random = new Random(20260722L);
        RandomGenerator<TransferOp> gen = opArb.generator(1000);

        for (int seq = 0; seq < NUM_SEQUENCES; seq++) {
            List<Long> wallets = freshFundedPool();

            for (int i = 0; i < SEQUENCE_LENGTH; i++) {
                TransferOp op = gen.next(random).value();
                Long from = wallets.get(op.fromIdx());
                Long to = wallets.get(op.toIdx());

                try {
                    ledgerService.transfer(from, to, Money.of(op.amountMinor(), USD));
                } catch (IllegalArgumentException insufficientFunds) {
                    // Legal outcome: an overdraft attempt is rejected, ledger untouched.
                }

                // INVARIANT 1: the entire ledger nets to zero, always.
                assertThat(globalSignedSum())
                        .as("global ledger must net to zero after op %s", op)
                        .isZero();

                // INVARIANT 2: no wallet ever goes negative.
                for (Long w : wallets) {
                    assertThat(getBalance(w))
                            .as("wallet %d must never be negative", w)
                            .isGreaterThanOrEqualTo(0L);
                }
            }
        }
    }

    // ---------- helpers ----------

    private Long createAccount(String type, String currency) {
        return jdbcClient
                .sql("INSERT INTO accounts(type, currency) VALUES (?, ?) RETURNING id")
                .params(type, currency)
                .query(Long.class)
                .single();
    }

    private List<Long> freshFundedPool() {
        Long cash = createAccount("CASH", "USD");
        List<Long> wallets = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            Long wallet = createAccount("USER_WALLET", "USD");
            fundAccount(cash, wallet, INITIAL_FUNDING);
            wallets.add(wallet);
        }
        return wallets;
    }

    private void fundAccount(Long cashId, Long walletId, long amountMinor) {
        Long txnId = jdbcClient
                .sql("INSERT INTO transactions DEFAULT VALUES RETURNING id")
                .query(Long.class)
                .single();

        // Both sides in ONE statement so the deferred balance trigger sees a
        // complete, balanced transaction (mirrors the transfer posting).
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """)
                .params(txnId, cashId, amountMinor, txnId, walletId, amountMinor)
                .update();
    }

    private long globalSignedSum() {
        return jdbcClient
                .sql("SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries")
                .query(Long.class)
                .single();
    }

    private long getBalance(Long accountId) {
        return jdbcClient
                .sql("SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }
}
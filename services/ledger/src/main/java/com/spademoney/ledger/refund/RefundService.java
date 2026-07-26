package com.spademoney.ledger.refund;

import java.util.Currency;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.query.AccountBalances;
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.service.LedgerTransactionService;

/**
 * Refunds, as reversing entries.
 *
 * The original transaction is never touched. Entries are append-only (the
 * entries_immutable trigger enforces it), so a refund is a NEW transaction of
 * type REFUND that names what it undoes via reverses_transaction_id. That link
 * is the only thing tying the two together, and ck_reverses_only_refunds makes
 * it a two-way rule: only a REFUND may name a reversed transaction, and it must
 * name one.
 *
 * "How much has already been refunded" is therefore always DERIVED by summing
 * the entries of every REFUND pointing at the original -- never a running total
 * cached on the original row, which could drift from the entries that are the
 * actual source of truth (principle 7 / ADR-002).
 */
@Service
public class RefundService {

    /**
     * Sum of every refund already posted against one transaction. One debit per
     * refund, so summing the debit side counts each refund exactly once.
     */
    private static final String REFUNDED_SO_FAR_SQL = """
            SELECT COALESCE(SUM(e.amount_minor), 0)
              FROM transactions t
              JOIN entries e ON e.transaction_id = t.id
             WHERE t.reverses_transaction_id = ?
               AND t.type = 'REFUND'
               AND e.direction = 'DEBIT'
            """;

    private record OriginalEntry(Long accountId, String direction, long amountMinor, String currency) {
    }

    private final JdbcClient jdbcClient;
    private final AccountBalances balances;
    private final LedgerTransactionService ledger;

    public RefundService(JdbcClient jdbcClient, AccountBalances balances, LedgerTransactionService ledger) {
        this.jdbcClient = jdbcClient;
        this.balances = balances;
        this.ledger = ledger;
    }

    @Transactional
    public RefundResponse refund(RefundRequest request) {
        final long originalId = request.transactionId();
        final long amountMinor = request.amountMinor();

        String type = jdbcClient
                .sql("SELECT type FROM transactions WHERE id = ?")
                .param(originalId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new RefundTargetNotFoundException(originalId));

        if ("REFUND".equals(type)) {
            throw new UnrefundableTransactionException(originalId, "it is itself a refund");
        }

        List<OriginalEntry> entries = jdbcClient
                .sql("""
                        SELECT account_id, direction, amount_minor, currency
                          FROM entries WHERE transaction_id = ? ORDER BY id ASC
                        """)
                .param(originalId)
                .query((rs, n) -> new OriginalEntry(
                        rs.getLong("account_id"),
                        rs.getString("direction"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")))
                .list();

        if (entries.size() != 2) {
            throw new UnrefundableTransactionException(originalId,
                    "expected a simple two-entry posting, found " + entries.size() + " entries");
        }

        OriginalEntry originalDebit = side(entries, "DEBIT", originalId);
        OriginalEntry originalCredit = side(entries, "CREDIT", originalId);

        // Who paid and who received, in the ORIGINAL. A refund runs them backwards.
        final Long originalPayer = originalDebit.accountId();
        final Long originalPayee = originalCredit.accountId();
        final long originalAmount = originalDebit.amountMinor();
        final String currency = originalDebit.currency();

        // Ascending-id acquisition, exactly as the transfer path does (ADR-006):
        // a refund is a debit like any other, so it obeys the same lock rule and
        // cannot form a cycle with a concurrent transfer.
        //
        // This lock is also what makes the cap check below safe. Two concurrent
        // refunds of the same transaction contend on the same two account rows,
        // so they serialize -- without it both could read the same
        // "already refunded" total and together exceed the original.
        ledger.lockBothAscending(originalPayee, originalPayer, currency);

        long refundedSoFar = jdbcClient.sql(REFUNDED_SO_FAR_SQL)
                .param(originalId)
                .query(Long.class)
                .single();

        long refundedAfter = Math.addExact(refundedSoFar, amountMinor);
        if (refundedAfter > originalAmount) {
            throw new RefundExceedsOriginalException(originalId, amountMinor, refundedSoFar, originalAmount);
        }

        // The refund debits whoever was PAID. No account is allowed to go
        // negative, so a merchant who has already moved the money out cannot be
        // refunded from an empty balance. In production this is where a platform
        // reserve (the CLEARING account) or an underwritten negative balance
        // would absorb the difference -- that is a question of who carries the
        // risk, not one the ledger can answer on its own.
        long available = balances.available(originalPayee);
        if (available < amountMinor) {
            throw new InsufficientFundsException(available, amountMinor);
        }

        Long refundTransactionId = ledger.createTransaction("REFUND", originalId);
        Money amount = Money.of(amountMinor, Currency.getInstance(currency));
        ledger.postDoubleEntry(refundTransactionId, originalPayee, originalPayer, amount);

        return new RefundResponse(refundTransactionId, originalId, amountMinor, currency,
                refundedAfter, originalAmount - refundedAfter);
    }

    private static OriginalEntry side(List<OriginalEntry> entries, String direction, long transactionId) {
        return entries.stream()
                .filter(e -> direction.equals(e.direction()))
                .findFirst()
                .orElseThrow(() -> new UnrefundableTransactionException(
                        transactionId, "no " + direction + " entry"));
    }
}

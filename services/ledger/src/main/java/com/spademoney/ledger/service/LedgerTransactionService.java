package com.spademoney.ledger.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.query.AccountBalances;

/**
 * The only class that writes to transactions and entries.
 *
 * The overdraft check reads AVAILABLE (posted minus active holds), never posted
 * alone. That is what keeps the safety invariant true:
 *
 *     for every account, at all times, posted >= sum(active holds)
 *
 * Because every debit path subtracts holds before deciding, a payer can never
 * spend down past what is reserved -- which is exactly why capture can post
 * unconditionally without re-checking funds.
 *
 * Both accounts are locked in ascending id order with FOR UPDATE. The lock is
 * taken BEFORE the balance read so no one can post an entry in the gap between
 * reading a balance and inserting against it; that gap is where double-spend
 * lives.
 */
@Service
public class LedgerTransactionService {

    private record AccountRow(Long id, String currency) {
    }

    private final JdbcClient jdbcClient;
    private final AccountBalances balances;

    public LedgerTransactionService(JdbcClient jdbcClient, AccountBalances balances) {
        this.jdbcClient = jdbcClient;
        this.balances = balances;
    }

    @Transactional
    public Long transfer(Long fromAccountId, Long toAccountId, Money amount) {
        lockBothAscending(fromAccountId, toAccountId, amount.currency().getCurrencyCode());

        long available = balances.available(fromAccountId);
        if (available < amount.amountMinor()) {
            throw new InsufficientFundsException(available, amount.amountMinor());
        }

        Long transactionId = createTransaction("TRANSFER", null);
        postDoubleEntry(transactionId, fromAccountId, toAccountId, amount);
        return transactionId;
    }

    /**
     * Ascending-id acquisition is the whole deadlock argument (ADR-006): two
     * transfers in opposite directions request the same two locks in the same
     * order, so no cycle can form.
     */
    public void lockBothAscending(Long a, Long b, String requestedCurrency) {
        Long lo = Math.min(a, b);
        Long hi = Math.max(a, b);

        var accounts = jdbcClient
                .sql("SELECT id, currency FROM accounts WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE")
                .params(lo, hi)
                .query((rs, rowNum) -> new AccountRow(rs.getLong("id"), rs.getString("currency")))
                .list();

        if (accounts.size() != 2) {
            throw new AccountNotFoundInLedgerException(lo, hi);
        }
        for (AccountRow row : accounts) {
            if (!row.currency().equals(requestedCurrency)) {
                throw new CurrencyMismatchException(row.id(), row.currency(), requestedCurrency);
            }
        }
    }

    /**
     * Ledger primitive. Public because capture and refund compose it with their
     * own policy: this service is the only thing that writes to transactions and
     * entries, but it is not the only thing that decides WHEN to.
     */
    public Long createTransaction(String type, Long reversesTransactionId) {
        return jdbcClient
                .sql("INSERT INTO transactions(type, reverses_transaction_id) VALUES (?, ?) RETURNING id")
                .params(type, reversesTransactionId)
                .query(Long.class)
                .single();
    }

    /**
     * Both sides in ONE statement so the deferred entries_balanced trigger sees
     * a complete, balanced transaction rather than firing mid-insert on a
     * one-sided posting.
     *
     * Ledger primitive, and deliberately unguarded: it posts what it is told.
     * Every caller is responsible for having established that the debit is
     * covered -- transfer and refund by checking available under a lock,
     * capture by the hold that already reserved the funds.
     */
    public void postDoubleEntry(Long transactionId, Long debitAccountId, Long creditAccountId, Money amount) {
        String currencyCode = amount.currency().getCurrencyCode();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, ?),
                    (?, ?, 'CREDIT', ?, ?)
                """)
                .params(transactionId, debitAccountId, amount.amountMinor(), currencyCode,
                        transactionId, creditAccountId, amount.amountMinor(), currencyCode)
                .update();
    }
}

package com.spademoney.ledger.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.money.Money;

@Service
public class LedgerTransactionService {

    private record AccountRow(Long id, String currency) {
    }

    private final JdbcClient jdbcClient;

    public LedgerTransactionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public Long transfer(Long fromAccountId, Long toAccountId, Money amount) {
        // ========== STEP 1: Lock both accounts in ascending order ==========
        Long account1Id = Math.min(fromAccountId, toAccountId);
        Long account2Id = Math.max(fromAccountId, toAccountId);

        var accounts = jdbcClient
                .sql("SELECT id, currency FROM accounts WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE")
                .params(account1Id, account2Id)
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("currency")))
                .list();

        if (accounts.size() != 2) {
            throw new IllegalArgumentException("One or both accounts not found");
        }

        var account1 = accounts.get(0);
        var account2 = accounts.get(1);

        // ========== STEP 2: Validate the money ==========
        String amountCurrencyCode = amount.currency().getCurrencyCode();

        if (!account1.currency().equals(amountCurrencyCode)) {
            throw new IllegalArgumentException(
                    "Account 1 currency mismatch: expected " + amountCurrencyCode + ", got " + account1.currency());
        }
        if (!account2.currency().equals(amountCurrencyCode)) {
            throw new IllegalArgumentException(
                    "Account 2 currency mismatch: expected " + amountCurrencyCode + ", got " + account2.currency());
        }
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        // ========== STEP 3: Check sender's posted balance ==========
        Long senderBalance = jdbcClient
                .sql("SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = ?")
                .param(fromAccountId)
                .query(Long.class)
                .single();

        if (senderBalance < amount.amountMinor()) {
            throw new IllegalArgumentException(
                    "Insufficient funds. Balance: " + senderBalance + ", requested: " + amount.amountMinor());
        }

        // ========== STEP 4: Create the transaction ==========
        Long transactionId = jdbcClient
                .sql("INSERT INTO transactions DEFAULT VALUES RETURNING id")
                .query(Long.class)
                .single();

        // ========== STEP 5: Post two entries (debit and credit) ==========
        String currencyCode = amount.currency().getCurrencyCode();

        // Both entries in ONE statement — trigger sees balanced transaction
        jdbcClient.sql("""
        INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
        VALUES
            (?, ?, 'DEBIT', ?, ?),
            (?, ?, 'CREDIT', ?, ?)
        """)
        .params(transactionId, fromAccountId, amount.amountMinor(), currencyCode,
                transactionId, toAccountId, amount.amountMinor(), currencyCode)
        .update();

        // ========== STEP 6: Return the transaction ID ==========
        return transactionId;
    }
}
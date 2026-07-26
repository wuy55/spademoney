package com.spademoney.ledger.query;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.account.BalanceView;
import com.spademoney.ledger.transfer.EntryView;
import com.spademoney.ledger.transfer.TransferView;

/**
 * Read side. Pure queries -- no locking, no mutation.
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final JdbcClient jdbcClient;
    private final AccountBalances balances;

    public LedgerQueryService(JdbcClient jdbcClient, AccountBalances balances) {
        this.jdbcClient = jdbcClient;
        this.balances = balances;
    }

    public Optional<BalanceView> findBalance(Long accountId) {
        Optional<String> currency = jdbcClient
                .sql("SELECT currency FROM accounts WHERE id = ?")
                .param(accountId)
                .query(String.class)
                .optional();

        if (currency.isEmpty()) {
            return Optional.empty();
        }

        long posted = balances.posted(accountId);
        long held = balances.held(accountId);
        return Optional.of(new BalanceView(accountId, currency.get(), posted, held, posted - held));
    }

    private record TransferHeader(Long id, String type, Long reversesTransactionId,
            String status, java.time.OffsetDateTime createdAt) {
    }

    public Optional<TransferView> findTransfer(Long transactionId) {
        Optional<TransferHeader> header = jdbcClient
                .sql("""
                        SELECT id, type, reverses_transaction_id, status, created_at
                          FROM transactions WHERE id = ?
                        """)
                .param(transactionId)
                .query((rs, rowNum) -> new TransferHeader(
                        rs.getLong("id"),
                        rs.getString("type"),
                        // Null for everything except a REFUND -- getObject, not
                        // getLong, so SQL NULL stays null instead of becoming 0.
                        rs.getObject("reverses_transaction_id", Long.class),
                        rs.getString("status"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<EntryView> entries = jdbcClient
                .sql("""
                        SELECT account_id, direction, amount_minor, currency
                        FROM entries WHERE transaction_id = ? ORDER BY id ASC
                        """)
                .param(transactionId)
                .query((rs, rowNum) -> new EntryView(
                        rs.getLong("account_id"),
                        rs.getString("direction"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")))
                .list();

        TransferHeader h = header.get();
        return Optional.of(new TransferView(h.id(), h.type(), h.reversesTransactionId(),
                h.status(), h.createdAt(), entries));
    }
}

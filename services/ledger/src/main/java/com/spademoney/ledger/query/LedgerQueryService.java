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
 * Read side. Pure queries — no locking, no mutation.
 *
 * Balances are derived by signed-summing the entries table, never read from a
 * cached column. A read-side cache would have to be reconciled against the
 * derived sum before it could be trusted.
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private static final String BALANCE_SQL = """
            SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
            FROM entries WHERE account_id = ?
            """;

    private final JdbcClient jdbcClient;

    public LedgerQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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

        long posted = jdbcClient
                .sql(BALANCE_SQL)
                .param(accountId)
                .query(Long.class)
                .single();

        return Optional.of(new BalanceView(accountId, currency.get(), posted));
    }

    private record TransferHeader(Long id, String status, java.time.OffsetDateTime createdAt) {
    }

    public Optional<TransferView> findTransfer(Long transactionId) {
        Optional<TransferHeader> header = jdbcClient
                .sql("SELECT id, status, created_at FROM transactions WHERE id = ?")
                .param(transactionId)
                .query((rs, rowNum) -> new TransferHeader(
                        rs.getLong("id"),
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
        return Optional.of(new TransferView(h.id(), h.status(), h.createdAt(), entries));
    }
}
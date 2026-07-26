package com.spademoney.ledger.hold;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.query.AccountBalances;
import com.spademoney.ledger.service.AccountNotFoundInLedgerException;
import com.spademoney.ledger.service.CurrencyMismatchException;
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.service.LedgerTransactionService;

/**
 * Authorization holds: reserve funds, then capture or release them.
 *
 * Authorizing and voiding post no ledger entries at all. An entry is a fact
 * about money that moved; a hold is state about money that has not. That is why
 * a void and an expiry need no compensating entries -- the hold simply leaves
 * ACTIVE and drops out of the available-balance subtraction. Modelling holds as
 * entries would mean writing ledger rows for events that never happened.
 */
@Service
public class HoldService {

    private static final Duration MIN_EXPIRY = Duration.ofSeconds(60);
    private static final Duration MAX_EXPIRY = Duration.ofDays(7);

    private record AccountRow(Long id, String currency) {
    }

    private record HoldState(String status, OffsetDateTime expiresAt) {
    }

    private final JdbcClient jdbcClient;
    private final AccountBalances balances;
    private final LedgerTransactionService ledger;

    public HoldService(JdbcClient jdbcClient, AccountBalances balances, LedgerTransactionService ledger) {
        this.jdbcClient = jdbcClient;
        this.balances = balances;
        this.ledger = ledger;
    }

    /**
     * Reserve funds. Locks ONLY the payer.
     *
     * The payee's balance is not constrained by an authorization, so locking it
     * would serialize every authorization against a hot merchant for no
     * correctness benefit. A transaction holding a single lock cannot be part of
     * a deadlock cycle, so this does not weaken ADR-006: the ordered rule still
     * governs every path that locks two accounts.
     */
    @Transactional
    public HoldResponse authorize(AuthorizeRequest request) {
        if (request.payerAccountId().equals(request.payeeAccountId())) {
            throw new IllegalArgumentException("Payer and payee must differ");
        }
        Duration expiry = Duration.ofSeconds(request.expiresInSeconds());
        if (expiry.compareTo(MIN_EXPIRY) < 0 || expiry.compareTo(MAX_EXPIRY) > 0) {
            throw new IllegalArgumentException(
                    "expiresInSeconds must be between " + MIN_EXPIRY.toSeconds() + " and " + MAX_EXPIRY.toSeconds());
        }

        AccountRow payer = jdbcClient
                .sql("SELECT id, currency FROM accounts WHERE id = ? FOR UPDATE")
                .param(request.payerAccountId())
                .query((rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("currency")))
                .optional()
                .orElseThrow(() -> new AccountNotFoundInLedgerException(
                        request.payerAccountId(), request.payeeAccountId()));

        // Read-only existence and currency check; no lock, nothing is constrained.
        AccountRow payee = jdbcClient
                .sql("SELECT id, currency FROM accounts WHERE id = ?")
                .param(request.payeeAccountId())
                .query((rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("currency")))
                .optional()
                .orElseThrow(() -> new AccountNotFoundInLedgerException(
                        request.payerAccountId(), request.payeeAccountId()));

        if (!payer.currency().equals(request.currency())) {
            throw new CurrencyMismatchException(payer.id(), payer.currency(), request.currency());
        }
        if (!payee.currency().equals(request.currency())) {
            throw new CurrencyMismatchException(payee.id(), payee.currency(), request.currency());
        }

        // Under the payer's lock: available already subtracts every other active
        // hold, so two concurrent authorizations can never over-reserve.
        long available = balances.available(request.payerAccountId());
        if (available < request.amountMinor()) {
            throw new InsufficientFundsException(available, request.amountMinor());
        }

        // expires_at is computed by the DATABASE, not by this JVM. Every check
        // that consumes it -- held()'s `expires_at > now()`, capture's
        // compare-and-set, the sweeper -- evaluates now() on the Postgres clock.
        // Deriving the deadline from the app clock instead would mean two
        // machines' clocks decide when an authorization lapses, so a few seconds
        // of drift would silently lengthen or shorten every hold. One clock owns
        // the whole comparison.
        return jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, ?, ?, now() + (?::bigint * interval '1 second'))
                RETURNING id, account_id, payee_account_id, amount_minor, currency, status, expires_at
                """)
                .params(request.payerAccountId(), request.payeeAccountId(),
                        request.amountMinor(), request.currency(), expiry.toSeconds())
                .query(HoldService::mapHold)
                .single();
    }

    /**
     * Claim authorized funds. Full capture takes the whole hold; partial capture
     * takes less and releases the rest.
     *
     * Performs NO funds check. Every debit path checks available (posted minus
     * active holds), so posted >= sum(active holds) is true for every account at
     * all times, which means the money this hold reserved is provably still
     * there. Re-checking would re-derive a guarantee authorization already
     * bought.
     *
     * Takes NO account lock either, for the same reason void does not: capture
     * can only INCREASE available balance. It lowers posted by the captured
     * amount and lowers held by the whole authorized amount, and captured <=
     * authorized, so available moves by (authorized - captured) >= 0. A
     * concurrent transfer holding the payer's lock can therefore only have read
     * an available balance that is stale-LOW, never stale-high -- its overdraft
     * decision stays valid.
     *
     * The `expires_at > now()` in the compare-and-set is load-bearing and is NOT
     * redundant with the status check. A lapsed hold still reads ACTIVE until the
     * sweeper relabels it, but it stopped reserving funds the moment it expired,
     * so those funds may already have been spent by another transfer. Capturing
     * on status alone would post entries with nothing behind them and drive the
     * payer negative -- money created from nothing.
     */
    @Transactional
    public CaptureResponse capture(long holdId, long amountMinor) {
        // The transaction must exist BEFORE the hold flips: ck_hold_resolution
        // requires that a CAPTURED hold names its capturing transaction. If the
        // compare-and-set below matches nothing we throw, and this insert rolls
        // back with everything else -- no orphan transaction survives.
        Long transactionId = ledger.createTransaction("CAPTURE", null);

        Optional<HoldResponse> captured = jdbcClient.sql("""
                UPDATE holds
                   SET status = 'CAPTURED', captured_transaction_id = ?, resolved_at = now()
                 WHERE id = ? AND status = 'ACTIVE' AND expires_at > now()
                RETURNING id, account_id, payee_account_id, amount_minor, currency, status, expires_at
                """)
                .params(transactionId, holdId)
                .query(HoldService::mapHold)
                .optional();

        if (captured.isEmpty()) {
            throw diagnoseUncapturable(holdId);
        }
        HoldResponse hold = captured.get();

        // Under-capture is normal; over-capture would post funds the
        // authorization never reserved.
        if (amountMinor > hold.amountMinor()) {
            throw new CaptureExceedsHoldException(holdId, amountMinor, hold.amountMinor());
        }

        Money amount = Money.of(amountMinor, Currency.getInstance(hold.currency()));
        ledger.postDoubleEntry(transactionId, hold.accountId(), hold.payeeAccountId(), amount);

        return new CaptureResponse(hold.holdId(), transactionId, amountMinor,
                hold.amountMinor() - amountMinor, hold.currency(), hold.status());
    }

    /**
     * Rowcount 0 on the capture compare-and-set conflates three causes. Re-read
     * to say which; all three are terminal for the caller, but they are three
     * different things to tell a merchant.
     */
    private RuntimeException diagnoseUncapturable(long holdId) {
        HoldState state = jdbcClient
                .sql("SELECT status, expires_at FROM holds WHERE id = ?")
                .param(holdId)
                .query((rs, n) -> new HoldState(
                        rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (!"ACTIVE".equals(state.status())) {
            return new HoldNotActiveException(holdId, state.status());
        }
        // Still ACTIVE, so the only remaining reason the predicate failed is time.
        return new HoldExpiredException(holdId, state.expiresAt());
    }

    /**
     * Cancel a reservation. Posts nothing and takes NO account lock: voiding
     * only ever increases available balance, so it cannot break the balance
     * invariant and has nothing to serialize against.
     *
     * The conditional UPDATE is a compare-and-set. Postgres serializes the two
     * writers on the row, so of two concurrent void-and-capture attempts exactly
     * one sees rowcount 1. This is only possible because a hold has a single
     * edge out of ACTIVE -- a running-total design would need a read-decide-write
     * under FOR UPDATE instead.
     */
    @Transactional
    public HoldResponse voidHold(long holdId) {
        Optional<HoldResponse> updated = jdbcClient.sql("""
                UPDATE holds
                   SET status = 'VOIDED', resolved_at = now()
                 WHERE id = ? AND status = 'ACTIVE'
                RETURNING id, account_id, payee_account_id, amount_minor, currency, status, expires_at
                """)
                .param(holdId)
                .query(HoldService::mapHold)
                .optional();

        if (updated.isPresent()) {
            return updated.get();
        }

        // Rowcount 0 means either no such hold, or it is no longer ACTIVE.
        // Re-read to say which; both are terminal for the caller.
        String status = jdbcClient
                .sql("SELECT status FROM holds WHERE id = ?")
                .param(holdId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        throw new HoldNotActiveException(holdId, status);
    }

    /** Package-private so the read side maps rows identically to the write side. */
    static HoldResponse mapHold(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new HoldResponse(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getLong("payee_account_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }
}

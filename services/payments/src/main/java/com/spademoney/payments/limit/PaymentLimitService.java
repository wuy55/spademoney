package com.spademoney.payments.limit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payer's spending cap — the one rule Payments enforces on its own.
 *
 * It matters out of proportion to its size, because it is the only step of the
 * saga whose state lives in <em>this</em> database. Everything else is a command
 * to another service. That is what makes the saga a saga rather than a retrying
 * HTTP client: there is a local commit sitting between two remote effects, and
 * no transaction can span them.
 *
 * <h2>Consumed totals are derived, never cached</h2>
 * "How much of the cap is used" is summed from {@code limit_consumptions} every
 * time, exactly as the Ledger derives refunded-so-far from entries rather than
 * storing a running total (ADR-002, principle 7). A counter on
 * {@code payment_limits} would be faster and would eventually disagree with the
 * rows it summarises — and a cap that has silently drifted is worse than no cap,
 * because everyone believes it.
 *
 * <h2>The cap is a lifetime total, and that is a simplification</h2>
 * A real limit is per rolling window: per day, per month. That is a
 * {@code WHERE consumed_at > now() - interval} away and changes nothing about
 * the saga, which is why it is left out — the interesting property here is the
 * check-and-record atomicity, not the calendar.
 */
@Service
public class PaymentLimitService {

    /**
     * The check and the record, serialized.
     *
     * The lock is taken on the cap row before the total is read, for the same
     * reason the Ledger locks an account before reading its balance: without it,
     * two concurrent payments both read "5,000 used of 10,000", both decide
     * 4,000 is fine, and together they spend 13,000. Reading and deciding are
     * only safe under something that stops the answer changing underneath.
     *
     * An account with no cap row is unlimited, and the fast path skips the lock
     * entirely — there is nothing to contend over.
     */
    private static final String LOCK_CAP_SQL = """
            SELECT cap_minor, currency FROM payment_limits WHERE account_id = ? FOR UPDATE
            """;

    private static final String CONSUMED_SQL = """
            SELECT COALESCE(SUM(amount_minor), 0)
              FROM limit_consumptions
             WHERE account_id = ? AND currency = ? AND released_at IS NULL
            """;

    /**
     * ON CONFLICT DO NOTHING makes consuming idempotent per saga. A driver that
     * crashed after this insert and before marking the step succeeded comes back
     * and runs it again; without the conflict clause that retry would charge the
     * payer's cap twice for one payment.
     */
    private static final String CONSUME_SQL = """
            INSERT INTO limit_consumptions (saga_id, account_id, amount_minor, currency)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (saga_id) DO NOTHING
            """;

    private record Cap(long capMinor, String currency) {
    }

    /** What a consume attempt decided, and why. */
    public sealed interface Decision {
        record Consumed(long capMinor, long consumedMinor) implements Decision {
        }

        record Unlimited() implements Decision {
        }

        record Exceeded(long capMinor, long consumedMinor, long requestedMinor) implements Decision {
        }

        record CurrencyMismatch(String capCurrency, String requestedCurrency) implements Decision {
        }
    }

    private final JdbcClient jdbcClient;

    public PaymentLimitService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public Decision consume(UUID sagaId, long accountId, long amountMinor, String currency) {
        Optional<Cap> cap = jdbcClient.sql(LOCK_CAP_SQL)
                .param(accountId)
                .query((rs, n) -> new Cap(rs.getLong("cap_minor"), rs.getString("currency")))
                .optional();

        if (cap.isEmpty()) {
            // No cap configured. Nothing is recorded, so nothing has to be
            // released if the saga later turns around.
            return new Decision.Unlimited();
        }
        if (!cap.get().currency().equals(currency)) {
            // A cap in one currency says nothing about an amount in another, and
            // this service does no FX. Refusing is the only honest answer.
            return new Decision.CurrencyMismatch(cap.get().currency(), currency);
        }

        long consumed = jdbcClient.sql(CONSUMED_SQL)
                .params(accountId, currency)
                .query(Long.class)
                .single();

        // A replay of an already-consumed saga must not be counted against
        // itself. Its own row is included in the sum above, so subtract it back
        // out before deciding.
        long alreadyMine = jdbcClient.sql("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM limit_consumptions
                 WHERE saga_id = ? AND released_at IS NULL
                """).param(sagaId).query(Long.class).single();

        long wouldBe = consumed - alreadyMine + amountMinor;
        if (wouldBe > cap.get().capMinor()) {
            return new Decision.Exceeded(cap.get().capMinor(), consumed - alreadyMine, amountMinor);
        }

        jdbcClient.sql(CONSUME_SQL).params(sagaId, accountId, amountMinor, currency).update();
        return new Decision.Consumed(cap.get().capMinor(), wouldBe);
    }

    /**
     * Give the cap back. Idempotent by construction: the predicate excludes rows
     * already released, so running it twice releases nothing the second time.
     *
     * The row is kept rather than deleted. A released consumption is the
     * evidence that a compensation ran, and reconciliation reads it -- deleting
     * it would make a compensated saga indistinguishable from one that never
     * consumed anything.
     */
    @Transactional
    public int release(UUID sagaId) {
        return jdbcClient.sql("""
                UPDATE limit_consumptions SET released_at = now()
                 WHERE saga_id = ? AND released_at IS NULL
                """).param(sagaId).update();
    }

    @Transactional
    public void setCap(long accountId, long capMinor, String currency) {
        jdbcClient.sql("""
                INSERT INTO payment_limits (account_id, cap_minor, currency)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE
                   SET cap_minor = EXCLUDED.cap_minor, currency = EXCLUDED.currency
                """).params(accountId, capMinor, currency).update();
    }

    public Optional<PaymentLimitView> find(long accountId) {
        return jdbcClient.sql("""
                SELECT l.account_id, l.cap_minor, l.currency,
                       COALESCE((SELECT SUM(c.amount_minor) FROM limit_consumptions c
                                  WHERE c.account_id = l.account_id
                                    AND c.currency = l.currency
                                    AND c.released_at IS NULL), 0) AS consumed_minor
                  FROM payment_limits l WHERE l.account_id = ?
                """)
                .param(accountId)
                .query((rs, n) -> new PaymentLimitView(
                        rs.getLong("account_id"), rs.getLong("cap_minor"),
                        rs.getString("currency"), rs.getLong("consumed_minor")))
                .optional();
    }
}

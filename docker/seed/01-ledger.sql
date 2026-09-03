-- Demo seed for the Ledger, run by the `seed` service on every `compose up`.
--
-- Idempotent by construction: it does nothing at all if any account already
-- exists. That matters because this runs on EVERY `compose up`, not just the
-- first -- unlike docker-entrypoint-initdb.d scripts, which are skipped once the
-- data volume exists. Seeding money is not something to do twice by accident, so
-- the guard is "the ledger is empty", not "insert if not exists" per row.
--
-- This cannot live in the Postgres init scripts, and the reason is worth
-- knowing: those run before the Ledger has ever started, so `accounts` does not
-- exist yet -- Flyway creates it. Seeding therefore has to wait for the
-- application to be healthy, which is what the `seed` service's depends_on
-- expresses.

DO $$
DECLARE
    cash_id  BIGINT;
    payer_id BIGINT;
    payee_id BIGINT;
    txn_id   BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM accounts) THEN
        RAISE NOTICE 'ledger already seeded; leaving it alone';
        RETURN;
    END IF;

    -- CASH is the system's side of every funding. It goes negative by
    -- construction, and that is correct double-entry rather than a bug -- see
    -- the NO_NEGATIVE_WALLETS reconciliation check, which is scoped to
    -- USER_WALLET for exactly this reason.
    INSERT INTO accounts (type, currency) VALUES ('CASH', 'USD')        RETURNING id INTO cash_id;
    INSERT INTO accounts (type, currency) VALUES ('USER_WALLET', 'USD') RETURNING id INTO payer_id;
    INSERT INTO accounts (type, currency) VALUES ('USER_WALLET', 'USD') RETURNING id INTO payee_id;

    -- Funded as a balanced double entry, not a bare credit. An unbalanced
    -- funding would be refused by the deferred entries_balanced trigger, and
    -- seeding through the same rules as everything else is the point: the demo
    -- data is not privileged.
    INSERT INTO transactions (type) VALUES ('TRANSFER') RETURNING id INTO txn_id;
    INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency) VALUES
        (txn_id, payer_id, 'CREDIT', 1000000, 'USD'),
        (txn_id, cash_id,  'DEBIT',  1000000, 'USD');

    RAISE NOTICE 'seeded ledger: cash=% payer=% payee=% (payer funded 1000000 USD minor)',
        cash_id, payer_id, payee_id;
END $$;

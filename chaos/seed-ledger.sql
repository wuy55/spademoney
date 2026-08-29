-- Deterministic starting state for the Ledger, used by the smoke and chaos
-- scripts.
--
-- RESTART IDENTITY is what makes the account ids predictable (1, 2, 3), so the
-- scripts can name them without parsing anything back. Every number a script
-- later asserts is derived from this file, which is why it lives next to them
-- rather than in a fixture nobody reads.
TRUNCATE outbox, holds, entries, transactions, idempotency_keys, accounts
    RESTART IDENTITY CASCADE;

-- 1 = CASH (the system's side of every funding; negative by construction)
-- 2 = payer wallet
-- 3 = payee wallet
INSERT INTO accounts (type, currency) VALUES
    ('CASH',        'USD'),
    ('USER_WALLET', 'USD'),
    ('USER_WALLET', 'USD');

-- Fund the payer. Written as a balanced double entry rather than a bare credit,
-- because an unbalanced funding would be caught by the deferred constraint
-- trigger -- and, if it somehow were not, by the reconciliation the scripts run
-- at the end. Seeding through the same rules as everything else is the point.
INSERT INTO transactions (type) VALUES ('TRANSFER');
INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency) VALUES
    (1, 2, 'CREDIT', 1000000, 'USD'),
    (1, 1, 'DEBIT',  1000000, 'USD');

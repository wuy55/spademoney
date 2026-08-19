-- Payments' own schema, in its own database, with its own Flyway history table.
-- Nothing in this file is shared with the Ledger and nothing here can read the
-- Ledger's tables; a query that tries is a connection error, not a slow join.

-- Per-account spending cap, in minor units, checked by the saga's first step
-- from Session 9 onward. Nothing reads it today.
--
-- It is created now because an empty table costs nothing and a migration
-- written while the schema is calm is a better migration than one written
-- alongside the saga that needs it.
CREATE TABLE payment_limits (
    -- Deliberately NOT a foreign key. The accounts it names live in the
    -- Ledger's database, where a constraint cannot reach. Referential
    -- integrity across a service boundary is the saga's job, not the
    -- engine's -- which is the whole reason M3 has a saga.
    account_id  BIGINT      PRIMARY KEY,

    -- Integer minor units, matching the Ledger's money representation
    -- (ADR-001). No floats cross this boundary in either direction.
    cap_minor   BIGINT      NOT NULL CHECK (cap_minor > 0),

    -- ISO-4217. A cap only means something against an amount in the same
    -- currency; the Session 9 check compares both.
    currency    CHAR(3)     NOT NULL,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE payment_limits IS
    'Per-account spending caps enforced by the payment saga. Unused until Session 9.';

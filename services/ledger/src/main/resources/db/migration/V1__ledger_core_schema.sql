-- V1__ledger_core_schema.sql
-- Milestone 1: ledger core. Accounts, transactions, immutable entries, idempotency.
-- Invariants enforced HERE (not just in app code):
--   (1) entries are append-only               -> forbid_mutation trigger
--   (2) every transaction nets to zero per ccy -> deferred constraint trigger
--   (3) amounts are always positive minor units -> CHECK; direction carries sign

-- ---------------------------------------------------------------------------
-- accounts
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type        TEXT        NOT NULL CHECK (type IN ('USER_WALLET','CASH','CLEARING','FEES')),
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- transactions
-- A transfer is atomic and immediately POSTED in M1 (holds arrive in M2).
-- Idempotency lives in its own table (see idempotency_keys) rather than here,
-- so the API concern (fingerprint, stored HTTP response, per-endpoint scope)
-- does not leak into the ledger domain table.
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status      TEXT        NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- entries  (immutable, append-only; direction carries the sign)
-- ---------------------------------------------------------------------------
CREATE TABLE entries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  BIGINT      NOT NULL REFERENCES transactions(id),
    account_id      BIGINT      NOT NULL REFERENCES accounts(id),
    direction       TEXT        NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3)     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account ON entries (account_id);
CREATE INDEX idx_entries_txn     ON entries (transaction_id);

-- Invariant (1): entries are append-only. Corrections are reversing entries,
-- never UPDATE/DELETE (brief §3.2).
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'entries are append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_immutable
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Invariant (2): every transaction is balanced per currency at COMMIT.
-- DEFERRABLE INITIALLY DEFERRED so it fires once at commit, after all of a
-- transaction's entries are inserted -- not mid-insert when only one side
-- exists. Positive-only amounts mean a net of zero forces >=2 entries.
-- Written per-currency now so multi-currency FX (Backlog #1) needs no rework.
CREATE OR REPLACE FUNCTION assert_transaction_balanced() RETURNS trigger AS $$
DECLARE
    unbalanced INT;
BEGIN
    SELECT count(*) INTO unbalanced
    FROM (
        SELECT transaction_id, currency,
               sum(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS net
        FROM entries
        WHERE transaction_id = NEW.transaction_id
        GROUP BY transaction_id, currency
    ) s
    WHERE s.net <> 0;

    IF unbalanced > 0 THEN
        RAISE EXCEPTION 'transaction % is not balanced (per-currency net <> 0)', NEW.transaction_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entries_balanced
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- ---------------------------------------------------------------------------
-- idempotency_keys  (brief §5.4; ADR-005: lives in Postgres, commits atomically
-- with the money movement it protects). Scoped per endpoint.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    endpoint             TEXT        NOT NULL,
    idempotency_key      TEXT        NOT NULL,
    request_fingerprint  TEXT        NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    response_status      INT,
    response_body        TEXT,
    transaction_id       BIGINT      REFERENCES transactions(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    PRIMARY KEY (endpoint, idempotency_key)
);
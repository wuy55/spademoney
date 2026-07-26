-- V2__holds_and_reversals.sql
-- Authorization holds, and refunds expressed as reversing entries.
-- Invariants enforced HERE (not just in app code):
--   (4) only a REFUND names a reversed transaction   -> ck_reverses_only_refunds
--   (5) a hold's terminal state is final             -> holds_terminal_is_final trigger
--   (6) status and resolution columns always agree   -> ck_hold_resolution

-- ---------------------------------------------------------------------------
-- transactions: provenance
-- A capture and a plain transfer post identical entries; without `type` they
-- are indistinguishable after the fact. `reverses_transaction_id` is how a
-- refund names what it undoes, since entries are append-only and the original
-- can never be modified.
-- ---------------------------------------------------------------------------
ALTER TABLE transactions
    ADD COLUMN type TEXT NOT NULL DEFAULT 'TRANSFER'
        CHECK (type IN ('TRANSFER','CAPTURE','REFUND')),
    ADD COLUMN reverses_transaction_id BIGINT REFERENCES transactions(id);

-- The DEFAULT existed only to backfill existing rows. Drop it so every future
-- insert must state its kind: a capture silently recorded as a TRANSFER is a
-- reporting bug that surfaces months later.
ALTER TABLE transactions ALTER COLUMN type DROP DEFAULT;

ALTER TABLE transactions
    ADD CONSTRAINT ck_reverses_only_refunds CHECK (
        (type =  'REFUND' AND reverses_transaction_id IS NOT NULL)
     OR (type <> 'REFUND' AND reverses_transaction_id IS NULL)
    );

CREATE INDEX idx_transactions_reverses ON transactions (reverses_transaction_id);

-- ---------------------------------------------------------------------------
-- holds
-- Deliberately NOT append-only, unlike entries. An entry is a fact about money
-- that moved; a hold is state about money that has not. A hold that expires
-- would otherwise need a reversing entry for an event that never happened.
--
-- payee_account_id is captured at authorization time, as a card auth names the
-- merchant up front. Capture therefore takes no account arguments at all: it
-- resolves the hold and cannot post into the wrong account.
--
-- There is deliberately no captured_amount_minor column: it is the sum of the
-- capture transaction's entries, reachable via captured_transaction_id.
-- Storing it would be a cached balance under another name (see principle 7).
-- ---------------------------------------------------------------------------
CREATE TABLE holds (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id              BIGINT      NOT NULL REFERENCES accounts(id),
    payee_account_id        BIGINT      NOT NULL REFERENCES accounts(id),
    amount_minor            BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3)     NOT NULL,
    status                  TEXT        NOT NULL DEFAULT 'ACTIVE'
                                        CHECK (status IN ('ACTIVE','CAPTURED','VOIDED','EXPIRED')),
    captured_transaction_id BIGINT      REFERENCES transactions(id),
    expires_at              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,

    CONSTRAINT ck_hold_accounts_differ CHECK (account_id <> payee_account_id),

    CONSTRAINT ck_hold_resolution CHECK (
        (status =  'ACTIVE'   AND captured_transaction_id IS NULL     AND resolved_at IS NULL)
     OR (status =  'CAPTURED' AND captured_transaction_id IS NOT NULL AND resolved_at IS NOT NULL)
     OR (status IN ('VOIDED','EXPIRED')
                              AND captured_transaction_id IS NULL     AND resolved_at IS NOT NULL)
    )
);

-- Access path for available_balance: the active holds on one account.
CREATE INDEX idx_holds_account_active ON holds (account_id) WHERE status = 'ACTIVE';
-- Access path for the expiry sweeper.
CREATE INDEX idx_holds_expiry ON holds (expires_at) WHERE status = 'ACTIVE';

-- Invariant (5): once a hold leaves ACTIVE it never returns. This is what makes
-- double-capture and capture-after-void impossible in the DATABASE rather than
-- only in application code, and it is why capture can be a single conditional
-- UPDATE (compare-and-set) instead of a read-decide-write under a row lock.
CREATE OR REPLACE FUNCTION forbid_hold_resurrection() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'hold % is already %; terminal states are final', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER holds_terminal_is_final
    BEFORE UPDATE ON holds
    FOR EACH ROW EXECUTE FUNCTION forbid_hold_resurrection();

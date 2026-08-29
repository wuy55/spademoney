-- V3__saga.sql
-- The orchestrated saga: Payments' memory of a payment in flight.
--
-- Why this table exists at all. A payment is now three steps -- authorize a hold
-- in the Ledger, consume the payer's local limit here, capture the hold -- and
-- they cannot share a transaction, because two of them are in another service's
-- database. A crash after step one is therefore a real state of the world, and
-- something has to be able to say what it was and what to do next. That is this
-- table. Without it, the only record of an in-flight payment is a stack frame,
-- and a stack frame does not survive `docker kill`.
--
-- ORCHESTRATION, not choreography (ADR-003). One component decides what happens
-- next, and the decision is written down here before it is acted on. In a
-- choreographed version the "state" of a payment would be distributed across
-- the event history of every participant, which is elegant right up to the first
-- time somebody has to answer "why is this payment stuck".

CREATE TABLE sagas (
    id                    UUID        PRIMARY KEY,

    -- The caller's Idempotency-Key. UNIQUE, and that is what makes a client
    -- retry land on the SAME saga -- which is what makes every step key below
    -- deterministic. This one constraint is the fix for the double-charge
    -- window M3 opened deliberately in session 6.
    idempotency_key       TEXT        NOT NULL UNIQUE,

    -- Same four-case contract the Ledger implements: a key replayed with a
    -- different body is a client bug (422), not a replay. Checked before status,
    -- so a genuinely wrong reuse is reported even while the original is running.
    request_fingerprint   TEXT        NOT NULL,

    status                TEXT        NOT NULL CHECK (status IN
                                        ('RUNNING','COMPENSATING','COMPLETED','COMPENSATED','FAILED')),

    payer_account_id      BIGINT      NOT NULL,
    payee_account_id      BIGINT      NOT NULL,
    amount_minor          BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency              CHAR(3)     NOT NULL,

    -- Learned from the Ledger as the saga runs. Nullable because they do not
    -- exist yet when the saga is created, and a saga that fails at step one
    -- never acquires them.
    hold_id               BIGINT,
    ledger_transaction_id BIGINT,

    failure_code          TEXT,
    failure_message       TEXT,

    -- When the driver may next touch this saga. Backoff is expressed as a
    -- timestamp rather than a sleep so it survives a restart: a process that
    -- dies mid-backoff resumes the same schedule instead of retrying instantly.
    next_attempt_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A saga only turns around for a reason, and it always says what the reason
    -- was. Enforced here rather than trusted to the driver, because "FAILED with
    -- no reason" is the single most useless row a support engineer can be handed
    -- at 3am -- and COMPENSATING with no reason is worse, since that one is
    -- still moving.
    CONSTRAINT ck_saga_failure_reason CHECK (
        (status IN ('RUNNING','COMPLETED')                AND failure_code IS NULL)
     OR (status IN ('COMPENSATING','COMPENSATED','FAILED') AND failure_code IS NOT NULL)
    ),
    CONSTRAINT ck_saga_accounts_differ CHECK (payer_account_id <> payee_account_id)
);

-- The driver's access path: sagas that are still moving and are due. Partial, so
-- its cost tracks work in flight rather than payment history.
CREATE INDEX idx_sagas_due ON sagas (next_attempt_at)
    WHERE status IN ('RUNNING','COMPENSATING');

-- Reconciliation and the HoldExpired handler both start from a hold id.
CREATE INDEX idx_sagas_hold ON sagas (hold_id) WHERE hold_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- saga_steps
-- ---------------------------------------------------------------------------
CREATE TABLE saga_steps (
    saga_id         UUID        NOT NULL REFERENCES sagas(id) ON DELETE CASCADE,
    step            TEXT        NOT NULL,
    seq             INT         NOT NULL,
    kind            TEXT        NOT NULL CHECK (kind IN ('FORWARD','COMPENSATION')),
    status          TEXT        NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),

    -- saga:{sagaId}:{step}. Derived from ids that are fixed before the step
    -- first runs, so every retry of this step sends the SAME key -- which is
    -- what turns the Ledger's idempotency contract from decoration into an
    -- exactly-once guarantee. Session 6 sent a fresh UUID per attempt, so a
    -- retry was a second transfer.
    idempotency_key TEXT        NOT NULL,

    -- The exact body sent to the Ledger, written once when the step is created
    -- and RESENT verbatim on every retry.
    --
    -- This is not caching. The Ledger fingerprints the request body and answers
    -- 422 IDEMPOTENCY_KEY_REUSED when a known key arrives with a different one.
    -- A retry that rebuilt its body from saga state that had moved on in the
    -- meantime would hash differently and get 422 forever -- the saga would not
    -- fail, it would WEDGE, which is far harder to notice. Persisting the
    -- command removes the possibility.
    command         JSONB       NOT NULL,

    result          JSONB,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    PRIMARY KEY (saga_id, step),

    CONSTRAINT ck_step_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
     OR (status <> 'PENDING' AND completed_at IS NOT NULL)
    )
);

-- ---------------------------------------------------------------------------
-- limit_consumptions
-- The local step, and the only thing in this whole flow Payments owns outright.
-- ---------------------------------------------------------------------------
CREATE TABLE limit_consumptions (
    -- One row per saga: consuming the limit twice for one payment is the exact
    -- bug this key prevents, and it prevents it in the database rather than in
    -- the driver's control flow.
    saga_id      UUID        PRIMARY KEY REFERENCES sagas(id) ON DELETE CASCADE,
    account_id   BIGINT      NOT NULL,
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL,
    consumed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set when a compensating path releases it. Kept rather than deleted: a
    -- released consumption is evidence that a compensation ran, and
    -- reconciliation reads it.
    released_at  TIMESTAMPTZ
);

CREATE INDEX idx_limit_consumptions_account ON limit_consumptions (account_id)
    WHERE released_at IS NULL;

COMMENT ON TABLE limit_consumptions IS
    'Per-saga record of a payer''s consumed spending cap. Summed live; never cached on payment_limits.';

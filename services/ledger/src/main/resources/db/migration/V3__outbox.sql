-- V3__outbox.sql
-- The transactional outbox: how a fact about money leaves this service without
-- ever disagreeing with the money itself.
--
-- The problem it solves: "write to the database, then publish to the broker" has
-- no atomicity. Crash between the two and the money moved but nobody was told.
-- Publish first and the broker knows about a transfer that then rolled back.
-- There is no ordering of the two that is safe, because they are two systems.
--
-- The outbox removes the second system from the write path entirely. The event
-- is inserted HERE, in the same local transaction as the entries, so it commits
-- if and only if the money commits. A separate relay reads committed rows and
-- publishes them afterwards. That turns an impossible atomic-across-two-systems
-- problem into an ordinary local transaction plus an at-least-once delivery
-- problem -- and at-least-once plus a dedupe key is something a consumer can
-- actually solve (see Payments' inbox).

CREATE TABLE outbox (
    -- Publication order. The relay reads strictly ascending by this column, so
    -- events leave in the order they were committed. A UUID primary key would
    -- have made "what have I not sent yet, oldest first" unanswerable.
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- The consumer's dedupe key, and the reason at-least-once is survivable.
    --
    -- Defaulted in the DATABASE, on purpose. The invariant is that an event id
    -- is minted exactly once, when the event is written, and never by the relay:
    -- a relay that generated ids would emit a NEW id every time it republished
    -- after a crash, and the consumer's dedupe would never match anything. Making
    -- the default a column default means the relay is not merely discouraged
    -- from minting ids -- it has no code path that could.
    event_id        UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),

    -- Which thing this happened to. aggregate_id becomes the Kafka partition
    -- key, so every event about one hold or one transaction lands on one
    -- partition and is therefore consumed in order.
    aggregate_type  TEXT        NOT NULL CHECK (aggregate_type IN ('TRANSACTION','HOLD')),
    aggregate_id    TEXT        NOT NULL,

    event_type      TEXT        NOT NULL,

    -- The event as it will be published, already serialized. Stored rather than
    -- rebuilt at publish time for the same reason the saga persists its command
    -- bodies: a payload recomputed from state that has since moved on is not the
    -- event that happened.
    --
    -- JSONB, not TEXT, and the difference is worth knowing. JSONB is a parsed
    -- representation: Postgres validates the JSON at INSERT -- so an
    -- unserializable event can never commit alongside the money it describes --
    -- and it makes payloads queryable, which is what lets reconciliation ask
    -- "which events mention account 42". The cost is that JSONB normalises:
    -- keys come back reordered and whitespace regularised, so what the relay
    -- publishes is equivalent to, but not byte-identical with, what Jackson
    -- wrote. That is fine here because consumers parse JSON and key order
    -- carries no meaning. It would NOT be fine for anything fingerprinted or
    -- signed -- which is exactly why the saga's persisted command bodies, whose
    -- hash the Ledger compares, are a different decision made separately.
    payload         JSONB       NOT NULL,

    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL means "not yet acknowledged by the broker". Set only after a
    -- successful, acknowledged send. Crashing between the send and this update
    -- republishes the event -- at-least-once, which the inbox absorbs. Setting
    -- it before the send would be at-most-once, which loses events silently.
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- The relay's only access path: the unpublished backlog, oldest first. A partial
-- index means its cost tracks the size of the BACKLOG, not the size of the
-- table, so a relay that has kept up scans almost nothing however long the
-- service has been running.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS
    'Domain events written in the same transaction as the money they describe. Drained by OutboxRelay.';

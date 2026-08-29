-- V2__inbox.sql
-- The idempotent consumer's memory: every Ledger event this service has already
-- acted on.
--
-- Why this table has to exist at all. The Ledger's relay is at-least-once by
-- construction (it marks an event published only after the broker acknowledges
-- it, so a crash in that window republishes), and Kafka's own delivery is
-- at-least-once too: consumer offsets are committed to the BROKER, while the
-- effect of consuming is committed to THIS database, and there is no
-- transaction spanning the two. A crash after the effect and before the offset
-- commit redelivers an event that was already applied.
--
-- So duplicates are not an edge case to be minimised, they are guaranteed. The
-- only question is whether reprocessing one changes anything. This table is the
-- answer: the insert below and the effect of the event commit together, so a
-- redelivery finds the row already there and does nothing. At-least-once
-- delivery plus a dedupe key equals effectively-exactly-once EFFECTS, which is
-- the only kind of exactly-once anyone can actually build.

CREATE TABLE inbox_events (
    -- Minted by the Ledger's outbox INSERT and carried unchanged through the
    -- relay, the broker and this consumer. That chain is the whole guarantee:
    -- if any hop were allowed to mint a fresh id, a redelivery would look like
    -- a new event and this primary key would stop meaning anything.
    event_id        UUID        PRIMARY KEY,

    event_type      TEXT        NOT NULL,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,

    -- Kept, not just counted. This doubles as Payments' local record of what
    -- the Ledger says happened -- the only copy of that story on this side of
    -- the boundary, since Payments cannot read the Ledger's database. The
    -- reconciliation job compares it against Payments' own saga state.
    payload         JSONB       NOT NULL,

    -- Broker coordinates, for debugging a redelivery after the fact. Not part
    -- of the dedupe: offsets are not stable identity, they are positions.
    topic           TEXT        NOT NULL,
    partition       INT         NOT NULL,
    kafka_offset    BIGINT      NOT NULL,

    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reconciliation asks "what did the Ledger tell us about this hold?", which is
-- a lookup by aggregate rather than by event id.
CREATE INDEX idx_inbox_aggregate ON inbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE inbox_events IS
    'Dedupe record and local projection of Ledger events. One row per event_id, ever.';

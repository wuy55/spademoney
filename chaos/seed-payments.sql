-- Deterministic starting state for Payments.
--
-- inbox_events is truncated too, so a run's event count means "events this run
-- consumed" rather than "events since the volume was created". The chaos script
-- asserts on it to show the outbox survived the kill.
TRUNCATE sagas, saga_steps, limit_consumptions, payment_limits, inbox_events CASCADE;

-- A spending cap on the payer, so CONSUME_LIMIT records a consumption for every
-- payment and RELEASE_LIMIT has something to release. Without a cap the step is
-- a no-op and "no failed payment is still holding a spending cap" could never
-- fail. The cap equals the payer's funding in seed-ledger.sql, so it is never
-- what declines a payment: every run's outcomes are the same as without it.
INSERT INTO payment_limits (account_id, cap_minor, currency) VALUES (2, 1000000, 'USD');

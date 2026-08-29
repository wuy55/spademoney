-- Deterministic starting state for Payments.
--
-- inbox_events is truncated too, so a run's event count means "events this run
-- consumed" rather than "events since the volume was created". The chaos script
-- asserts on it to show the outbox survived the kill.
TRUNCATE sagas, saga_steps, limit_consumptions, payment_limits, inbox_events CASCADE;

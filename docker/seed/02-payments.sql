-- Demo seed for Payments. Idempotent per row, because a spending cap is
-- configuration rather than money -- re-applying it is harmless, so this one can
-- use ON CONFLICT instead of an emptiness guard.
--
-- The cap is set deliberately high. Its purpose here is to make the saga's
-- CONSUME_LIMIT step do real work on the happy path (it locks the cap row, sums
-- unreleased consumptions and records one) without declining the demo payment.
-- To watch the compensation path instead, lower it below the payment amount:
--
--   curl -X PUT localhost:8081/limits/2 -H 'Content-Type: application/json' \
--        -d '{"capMinor":1000,"currency":"USD"}'
--
-- ...then send a 2500 payment and watch it authorize, refuse on the cap, release
-- the cap and void the hold.

INSERT INTO payment_limits (account_id, cap_minor, currency)
VALUES (2, 500000, 'USD')
ON CONFLICT (account_id) DO UPDATE
    SET cap_minor = EXCLUDED.cap_minor,
        currency  = EXCLUDED.currency;

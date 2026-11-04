-- The table comment V1 wrote said the caps were unused until a later stage of
-- the build. They have been enforced since the saga shipped, so the comment a
-- \d+ shows is corrected here. V1 itself is left as written: Flyway checksums
-- every applied migration, and editing one would fail validation on every
-- database that has already run it.
COMMENT ON TABLE payment_limits IS
    'Per-account spending caps, enforced by the payment saga''s CONSUME_LIMIT step.';

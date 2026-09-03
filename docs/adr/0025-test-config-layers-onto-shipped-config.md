# ADR-0025: Test configuration layers onto shipped configuration, never replaces it

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

Tests need a few settings that differ from production: background schedulers off
so a test can drive them explicitly, Kafka's admin client quiet when no broker is
running.

The reflex is a file at `src/test/resources/application.yml`. That file does not
*merge* with `src/main/resources/application.yml` — it **shadows** it. Same
classpath resource name, and test-classes comes first. Every value in the shipped
configuration silently disappears in tests.

This had been latent in the Ledger since M1 and grew teeth the moment Kafka
arrived: the test file dropped the producer's `acks=all` and idempotence
settings, so the outbox relay's tests exercised a producer configured differently
from the one that ships.

It would have been worse in Payments. `LedgerTimeoutsTest` exists *specifically*
to fail if someone deletes the two HTTP timeout lines — Spring's default is
effectively "wait forever", which would turn the chaos demo into a hung terminal.
A shadowing test config would have left that test asserting a value it supplied
itself. **A test that cannot fail.**

## Decision

`application.yml` in both services declares:

```yaml
spring:
  config:
    import: "optional:classpath:/application-test-overrides.yml"
```

The overrides file is absent from the packaged jar and present on the test
classpath. It contains only the differences.

## Consequences

Tests run against the configuration that actually ships. Every value not
explicitly overridden is the real one, and a test asserting a shipped value is
asserting something that can fail.

The mechanism is a documented Spring Boot feature and the `optional:` prefix
makes it inert in production, but it is still a test-shaped hook living in a
production file. The alternative — a build-level `spring.config.additional-location`
in the Surefire configuration — keeps production config clean but does not apply
when tests run from an IDE, which is where most of them are run. The visible hook
that always works beats the invisible one that works in CI only.

The general lesson is the one this repository keeps relearning: **a test only
exercises what it explicitly drives.** ADR-0026 is the same failure in a different
costume, found a day later.

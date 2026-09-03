# ADR-0026: Background jobs are asserted to be scheduled, not assumed

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** Spencer Wu

## Context

`@EnableScheduling` was missing from `PaymentsApplication`. The saga driver's
`@Scheduled` trigger was therefore never registered, and **no saga could ever
advance in production**. Every payment was accepted with `202` and then sat in
`RUNNING` forever — no exception, no failed step, no log line, nothing in any
health check.

All 14 saga tests passed. They all call `SagaDriver.runOnce()` directly, which is
the *correct* way to test a state machine — it is what lets them assert on the
state between steps rather than racing a background thread — and it is precisely
why not one of them could notice that nothing calls it in production.

It was caught only by the compose smoke test, on the first run against the real
stack: a payment accepted, a `Location` header that resolved, and a status that
stayed `PENDING` through sixty seconds of polling with an empty `saga_steps`
table behind it.

## Decision

`SchedulingIsWiredTest` in both services asserts the **registered `@Scheduled`
tasks**, by name, via `ScheduledTaskHolder`.

## Consequences

The assertion has to be about the *task registry*, not the beans. The scheduler
bean exists either way — it is an ordinary `@Component`. What disappears without
the annotation is the post-processor that turns `@Scheduled` into a registered
task, so that is the thing to look at.

Confirmed the only way worth confirming it: by removing the annotation and
watching the test fail. A wiring test that has never been seen to fail is
indistinguishable from one that cannot.

The Ledger got the same test even though it has always had the annotation,
because its failure mode without it is the *quiet* one: money keeps moving,
events accumulate in the outbox, and no consumer ever hears about any of it. The
Ledger would look perfectly healthy while every downstream consumer silently fell
behind forever.

This is the same shape as ADR-0018's timeout bug and ADR-0025's shadowed config.
Three times now the gap has been between "the logic is right" and "the logic is
connected to anything", and all three times the unit suite was fully green while
it was broken. The generalisation worth carrying: **unit tests prove decisions;
only a running system proves wiring.** Anything that fires on a timer, listens on
a socket, or is invoked by a framework rather than by your own code needs one
test that asserts it is actually plugged in.

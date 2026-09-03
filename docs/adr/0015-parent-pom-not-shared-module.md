# ADR-0015: One parent pom, still no shared module

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Spencer Wu

## Context

Splitting into two services (ADR-0007) creates a question the single-module repo
never had: who decides the Java version, the Spring Boot version, the Jackson
generation?

If each service's pom decides for itself they can drift — Payments on one Boot
release, the Ledger on another — and the resulting bugs are invisible in both
poms, because neither is wrong on its own. Two services disagreeing over the wire
about how a field serialises is a genuinely unpleasant afternoon.

The obvious fix is a shared module. ADR-0007 forbids exactly that: a shared jar
is how a service boundary quietly becomes a monolith with extra steps.

## Decision

A parent aggregator pom at the repository root that manages versions for both
modules. **No shared code module.**

The distinction that makes this compatible with ADR-0007: a shared *library*
contains code that both services `import`, which is a compile-time coupling — change
the shared `Money` and both services must change together. A parent pom contains
no code. Its packaging is `pom`, it produces no jar, and nothing in it reaches
either service's classpath. It only answers "which version of third-party things
do you use".

**Sharing a build configuration is not sharing a runtime dependency.**

Payments duplicates the handful of wire shapes it needs from the Ledger rather
than importing them.

## Consequences

Version skew between the two services becomes impossible, and CI is one reactor
build — so a change to the root pom is tested against both consumers rather than
passing a Ledger-only build and breaking Payments.

The cost is that both services upgrade Boot together. For two services that is a
feature; at twenty it would be a coordination problem, which is one more reason
ADR-0007 fixes the count at two.

The claim is verified rather than asserted. `dependency:tree` for
`services/payments` contains zero `ledger` artifacts, and that command is the
proof rather than a comment saying so.

The duplicated wire shapes are the accepted cost. Two records that look alike is
the price of a refactor inside the Ledger being unable to break Payments'
compile — it can only break its tests against the published contract, which is
the failure you want, in the place you want it.

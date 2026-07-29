# ADR-0013: Hand-written OpenAPI, not springdoc

- **Status:** Accepted
- **Date:** 2026-07-26
- **Deciders:** Spencer Wu

## Context

The plan was springdoc-openapi, generating the specification from controller
annotations. That is the standard choice and it has a real advantage: a generated
spec cannot describe endpoints the code does not have.

It does not fit this service. springdoc's current release, 2.8.6 — the latest
across every artifact in the group — targets Spring Boot 3 on Framework 6 and is
built against Jackson 2. This service runs Boot 4.1 on Framework 7 with Jackson 3
(`tools.jackson`). Adding springdoc would drag a second Jackson onto the classpath
purely to produce documentation, in a service where the serialiser is part of the
idempotency contract because stored responses are replayed verbatim.

## Decision

Hand-write `docs/openapi.yaml` against OpenAPI 3.1.2, and do not add springdoc.

Prevent drift with `PaymentLifecycleContractTest`, which asserts over real HTTP
every status code, error code and `Location` header the document publishes — 15
tests that fail if the spec and the service disagree.

3.1.2 rather than the released 3.2.0 because nothing here uses a 3.2-only feature.
Declaring 3.2.0 would buy no capability while narrowing the set of tools that
accept the document, and moving up later is a one-line change.

## Consequences

No second Jackson on the classpath for documentation's sake.

The contract becomes a designed artifact rather than a by-product of annotations
— the same reasoning as [ADR-0008](0008-explicit-sql-over-jpa.md), applied to the
API surface instead of the persistence layer. The document can explain *why* a
`422` is a `422`, which generated descriptions do not.

The cost is the one real advantage given up: **a generated spec cannot lie about
the code, and a hand-written one can.** That is what makes the contract tests
load-bearing rather than nice-to-have. Without them this decision would be a
liability rather than a trade.

Examples must be written explicitly, because there is no schema-derived default to
fall back on. That turned out to be a feature. Rendering the document caught three
misleading examples that had been written carelessly, including a `TransferView`
sample showing two `DEBIT` entries — a double-entry document that did not sum to
zero. Generated examples would have produced exactly that, since they take the
first enum value.

The maintenance obligation is permanent and named: every new endpoint or error
code needs both the spec entry and the contract test, and the second is what makes
the first true. Revisit when springdoc ships Boot 4 support, at which point the
question becomes whether a generated spec is worth losing the prose.

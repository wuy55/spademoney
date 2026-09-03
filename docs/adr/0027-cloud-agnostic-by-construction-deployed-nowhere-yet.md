# ADR-0027: Cloud-agnostic by construction; deployed nowhere yet

- **Status:** Accepted
- **Date:** 2026-09-04
- **Deciders:** Spencer Wu

> **Implementation status:** this decision is binding but not yet realised as
> infrastructure. Everything here runs in one `docker compose` stack on one
> laptop. There is no Terraform, no cloud account, and no CI deployment step in
> this repository today. The record is written now because the constraint it
> imposes — no dependency on a specific cloud's proprietary surface — has been
> a rule since the first line of infrastructure code, not something to retrofit
> once a target is picked.

## Context

The project's own cost constraint (§1 of the brief) rules out a paid cloud
account for a six-week solo build: free to build, free for a reviewer to run,
`git clone && docker compose up` and nothing needs hosting or a credit card.

That constraint could have been satisfied two different ways. One is to build
against a specific cloud's managed primitives from the start and simulate them
locally — DynamoDB Local, LocalStack for S3/SQS, and so on — which is realistic
about *a* target but commits to it early. The other is to build against the
open primitive each managed service is a hosted version of, and treat "which
cloud" as a deployment-time decision rather than a design-time one.

The second is also the more defensible answer to an interview question, because
the target list spans companies on different clouds — some AWS-heavy, some
Azure, some GCP, some running Oracle infrastructure for legacy reasons, several
running more than one. A design that only makes sense on one of them is a worse
answer to "how would you deploy this here" at three-quarters of them.

## Decision

Nothing in the codebase names a specific cloud vendor. Concretely:

- **The broker is addressed through the Kafka wire protocol**, not through a
  Redpanda-specific client. `EventPublisher` and the inbox's `@KafkaListener`
  are written against `spring-kafka` and `org.apache.kafka.clients`; nothing
  imports a Redpanda API. Any Kafka-API-compatible broker — Amazon MSK, Azure
  Event Hubs' Kafka endpoint, Confluent Cloud on GCP, or Redpanda's own managed
  offering — is a `bootstrap.servers` change.
- **The database is plain PostgreSQL 16**, with no extension beyond what ships
  in core. `gen_random_uuid()` (`V3__outbox.sql`) has been built into Postgres
  core since version 13; it does not require `pgcrypto`, and nothing here
  requires an extension that only a specific vendor's managed Postgres ships.
- **No cloud SDK is imported anywhere.** Not `software.amazon.awssdk`, not
  `com.azure`, not `com.google.cloud`, not an Oracle Cloud client. Verified by
  searching every module's imports, not assumed.
- **All connection configuration is environment-variable driven** —
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME/PASSWORD`,
  `SPRING_KAFKA_BOOTSTRAP_SERVERS` — using Spring Boot's standard relaxed
  binding rather than a config file baked into the image. Pointing those at a
  managed Postgres endpoint and a managed Kafka-API endpoint instead of the
  compose network's `postgres`/`redpanda` hostnames is a deployment
  configuration, not a code change.
- **The services are stateless.** Saga state, idempotency claims and the
  outbox all live in Postgres, never in process memory (ADR-0005, ADR-0022's
  lease-based claim). Any instance can pick up any saga's next tick, which is
  what makes horizontal scale-out on a managed container platform a
  configuration change rather than a rewrite.

## Consequences

The system can be described honestly to an interviewer at any of the target
companies as "not built for a specific cloud, and here is exactly what changes
to run it on yours" — a stronger position than either having built it
cloud-specific for the wrong cloud, or having no answer at all. A worked
example, using AWS because it is the most common single target across the
list, is in `DESIGN.md` §13 and the README: RDS/Aurora Postgres Multi-AZ, MSK
(or a managed Redpanda), ECS Fargate or EKS, Secrets Manager. The same mapping
holds with Azure Database for PostgreSQL + Event Hubs + AKS, or Cloud SQL for
PostgreSQL + a Kafka-API broker + GKE, or Oracle's managed Postgres-compatible
offering and container service where that is the shop's standard — nothing in
the decision above prefers one of these over another.

The cost is real and is not hidden. Portability has a ceiling: nothing here
takes advantage of a specific cloud's differentiated primitives — no DynamoDB
single-digit-millisecond point lookups, no S3-backed tiered storage for the
event log, no a cloud-native secrets-rotation Lambda triggered on schedule.
Chasing full generality across every cloud's take on a managed service is also
how a project ends up using the least useful subset of all of them. The
counter-argument accepted here is that this project's claim was never "uses
cloud-native primitives well" — it is "the distributed-systems mechanism is
correct under failure" — and that claim is provable on a laptop with Docker,
which is exactly what the rest of this ADR log demonstrates.

`chaos/chaos-test.sh`'s assertions do not change under any of these targets
either. They read Postgres directly and ask both services' `/reconciliation`
endpoints a question over HTTP; none of that is a Docker-specific mechanism,
so the only change on a real cluster is what gets killed — a task or a pod
instead of a container — not what is asserted afterward.

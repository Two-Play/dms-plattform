# 0012: Database access with Magnum, ZIO Quill or plain JDBC

| Field | Value |
|---|---|
| Status | Open – spike required |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Already in place: PostgreSQL driver `42.7.14`, HikariCP `7.1.0` and Flyway `13.11.1` (`project/Dependencies.scala`). The access layer must support: row-level security with a tenant context set per session or transaction (`SET LOCAL app.tenant_id`, 0014), `FOR UPDATE SKIP LOCKED` (0010), JSONB for the content model and metadata (0007), an append-only audit table, and shared transactions with Flowable (0006). Doobie is ruled out (wrong ecosystem, 0003).

## Decision

Still open. Recommendation until the spike: Magnum `1.3.1` (core, JDBC, synchronous) with a thin custom ZIO wrapper (`ZIO.attemptBlocking`, transaction as `ZLayer`/scope). The `magnumzio` module is not used, as it only exists as `2.0.0-M4` (milestone).

## Alternatives considered

| Criterion | Recommendation: Magnum 1.3 + custom ZIO wrapper | ZIO Quill (ProtoQuill, Scala 3) | Plain JDBC + custom helpers |
|---|---|---|---|
| Maturity / version | Stable 1.3.1, ZIO module only a milestone | Stable, Scala 3 variant with restrictions | JDBC itself stable |
| SQL control | Full SQL, interpolator | Generated SQL from quoted DSL | Full SQL |
| Compile time | Low | High (macros) | None |
| `SET LOCAL` for RLS | Direct SQL in the transaction | Via raw SQL, transaction control to be checked | Direct |
| `SKIP LOCKED` | Direct SQL | Only via infix/raw SQL | Direct |
| JSONB | Custom `DbCodec` | Encoder required, restrictions | Manual |
| ZIO integration | Custom wrapper (approx. 100–200 lines) | Native (`ZIO[DataSource, …]`) | Custom wrapper |
| Boilerplate for CRUD | Low (repo derivation) | Low | High |
| Shared transaction with Flowable | Via shared `Connection` | Harder, own context | Via shared `Connection` |

## Consequences

### Positive

- With the recommendation, SQL stays readable and fully controllable; RLS and `SKIP LOCKED` are unproblematic.
- No dependency on a milestone artifact.

### Negative / Cost

- The custom ZIO wrapper must be maintained (transaction, error mapping, blocking pool).
- No compile-time checking of SQL against the schema; safeguarded only by integration tests (Testcontainers `0.44.1`).
- Until the spike result is available, there is uncertainty for all modules with persistence.

## Follow-ups

Spike criteria (evaluate per candidate):

1. `SET LOCAL app.tenant_id` at the start of the transaction; demonstrate that RLS policies take effect and that the context does not leak into the pool.
2. Job retrieval with `FOR UPDATE SKIP LOCKED` under two parallel workers without double processing.
3. Read and write JSONB and query it in `WHERE`.
4. Append-only audit: insert allowed, update/delete denied via DB permissions; errors mapped cleanly as ZIO errors.
5. Shared transaction with Flowable over the same `Connection`.
6. Compile time for ten repositories; readability of typical queries.
7. Testability with Testcontainers and ZIO Test.

- Document the result as a status change of this ADR.
- Ruled out: Doobie, Slick, Hibernate/JPA.

## Reversibility

**medium**: The access layer lives in the `*-core` modules behind repositories. A switch affects all repository implementations, but no `*-api`. The earlier the decision is made, the lower the effort.

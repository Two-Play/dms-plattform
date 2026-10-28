# 0012: Database access with Magnum, ZIO Quill or plain JDBC

| Field | Value |
|---|---|
| Status | Accepted (spike DMS-21, 2026-10-28) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Already in place: PostgreSQL driver `42.7.14`, HikariCP `7.1.0` and Flyway `13.11.1` (`project/Dependencies.scala`). The access layer must support: row-level security with a tenant context set per session or transaction (`SET LOCAL app.tenant_id`, 0014), `FOR UPDATE SKIP LOCKED` (0010), JSONB for the content model and metadata (0007), an append-only audit table, and shared transactions with Flowable (0006). Doobie is ruled out (wrong ecosystem, 0003).

## Decision

Magnum `1.3.1` (core only, JDBC, synchronous) with a thin custom ZIO wrapper. The transaction is owned by our own primitive: one connection per transaction, held in a ZIO `FiberRef`, which Magnum and Flowable (0006) both join. `magnumzio` (only `2.0.0-M4`), `magnumpg` and ZIO Quill are not used.

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

## Spike result (DMS-21, 2026-10-28)

Details, code and exact error messages: [`spikes/db-access/README.md`](../../spikes/db-access/README.md). All suites ran against PostgreSQL 17 (Magnum 13 tests, Quill 12, plain JDBC 1).

| Criterion | Magnum 1.3.1 + wrapper | ZIO Quill 4.x | Plain JDBC |
|---|---|---|---|
| 1 RLS tenant context, no leak into the pool | Pass | Pass | Pass |
| 2 `SKIP LOCKED`, two concurrent workers | Pass (40 jobs, no double claim) | Pass | – |
| 3 JSONB | Pass (opaque type codec) | Pass (raw SQL) | – |
| 4 Append-only audit, typed errors | Pass, commit errors typed | Commit failure becomes a defect; errors must be `Throwable` | – |
| 5 Shared connection with Flowable | Pass: `DbTx` built from the connection in our FiberRef | Only by seeding Quill's internal connection FiberRef (two FiberRefs to keep in sync) | Pass |
| 6 Clean compile, 10 repositories × 5 queries | 9.3 s | 24.5 s | 6.5 s |
| Runtime dependencies | 1 artifact | 49 artifacts (~60 MB, incl. Scala 2.13 compiler, scalameta) | none |

Findings that apply to every option:

- `SET LOCAL app.tenant_id = ?` cannot take a bind parameter; the tenant is set with `select set_config('app.tenant_id', ?, true)`.
- With no tenant set, the RLS policy raises an error instead of returning zero rows: decision needed in 0014.
- PostgreSQL reports an RLS violation and a missing grant with the same SQLSTATE 42501; error mapping must not rely on the code alone.
- Forked fibers inside a transaction share its connection: no parallelism inside a transaction.

Risk: `DbTx` has a `private[magnum]` constructor, so a three-line bridge lives in Magnum's package and must be re-checked on every Magnum upgrade (likely to change with Magnum 2.x).

## Follow-ups

- Add `com.augustnagro::magnum` 1.3.1; build the transaction primitive (`Db.transact`, FiberRef connection, `set_config` tenant, typed `DbError` by SQLSTATE) as a shared layer used by documents, audit and workflow.
- `Jsonb` opaque type codec; Testcontainers test layer running the Flyway migrations.
- Separate database roles: migration owner vs. application role without `BYPASSRLS`; `dms.audit_event` gets only `SELECT, INSERT`.
- Code review rule: no forked fibers inside `Db.transact`.

Original spike criteria (evaluate per candidate):

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

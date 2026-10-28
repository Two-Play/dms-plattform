# Spike DMS-21: database access library (ADR 0012)

Question: which library do we use for database access in the Scala 3.9 + ZIO 2 backend?

Candidates:

- **A** Magnum `1.3.1` (`com.augustnagro::magnum`), plus a hand-written ZIO wrapper
- **B** ZIO Quill `4.8.6` (`io.getquill::quill-jdbc-zio`, ProtoQuill for Scala 3)
- **C** Plain JDBC with small helpers (baseline)

**Result: A, Magnum 1.3.1 with our own thin ZIO wrapper.** All criteria pass, including the Flowable requirement (one
`java.sql.Connection` per transaction, owned outside the library and kept in a `FiberRef`). Quill passes the functional
criteria too. It loses on compile time (about 2.6x Magnum), on dependency weight (49 artifacts, about 60 MB), on error
typing (a failing commit becomes a defect), on release activity (last release October 2024), and because the hard
cases (`SET LOCAL`, `SKIP LOCKED`, JSONB filters) fall back to raw `sql"..."` anyway.

Everything here is time-boxed spike code, not production quality. It runs only against the throwaway database
`spike_db_access`.

## Setup and run

Prerequisites: Java 21 via coursier, scala-cli (tested with 1.8.0), and the `dms-plattform-db-1` container
(PostgreSQL 17.11 on `localhost:5433`).

```bash
cd spikes/db-access
./run.sh            # = ./setup.sh + scala-cli test --server=false {jdbc,magnum,quill}
./bench/measure.sh  # compile-time benchmark (generates 10 repositories per candidate, 3 clean compiles each)
./teardown.sh       # drops database spike_db_access and role spike_db_access_app
```

- `setup.sh` recreates database `spike_db_access` and applies `schema.sql`. It also creates the **non-superuser** login
  role `spike_db_access_app` (password `spike`, `NOBYPASSRLS`), so RLS applies. The database `dms` is not touched.
- Each candidate is its own scala-cli project (`//> using scala 3.9.0`). It does not depend on the sbt build.
  Assertions that must bypass RLS use a second pool as superuser `dms` (`AdminDs`).
- Always use `--server=false`. The Bloop server fails with Scala 3.9.0 (`sbt.internal.inc.InvalidComponent`, as seen in
  the Flowable spike).

| File | Content |
|---|---|
| `schema.sql` | RLS table `document`, job queue `job`, append-only `audit_event` (trigger as in `V1__grundschema.sql`), `engine_record` (stand-in for Flowable's `ACT_*` tables), `deferred_check` (constraint checked at COMMIT), grants |
| `common/Common.scala` | `DbError` ADT and mapping by SQLSTATE, Hikari pools, `AdminDs` |
| `common/SharedTx.scala` | Stand-in for the Flowable spike's `Tx`/`ExternalTx`: a transaction owned **outside** the library, connection kept in a `FiberRef` |
| `magnum/` | `MagnumDb.scala` (ZIO wrapper, about 60 lines without comments), `MagnumBridge.scala` (3 lines, see Problems), `Repos.scala`, `MagnumSpec.test.scala` (13 tests) |
| `quill/` | `QuillDb.scala` (repositories and tenant transaction), `QuillSpec.test.scala` (12 tests) |
| `jdbc/` | `JdbcDb.scala` (helpers and one repository), `JdbcSpec.test.scala` (1 test) |
| `bench/` | `generate.py` and `measure.sh` for criterion 6 |

## Versions checked

| Artifact | Version | Built with | Notes |
|---|---|---|---|
| `com.augustnagro:magnum_3` | 1.3.1 (Nov 2024) | `scala3-library` 3.3.0 | 3 artifacts in total, about 7 MB including scala-library. No JSONB codec in `magnumpg` 1.3.1, so `magnumpg` is not used. |
| `com.augustnagro:magnum_3` | 2.0.0-M4 (Oct 2026) | — | `magnumzio` exists only here (milestone), so it is not used. |
| `io.getquill:quill-jdbc-zio_3` | 4.8.6 (30 Oct 2024, still latest) | `scala3-library` 3.3.4 | 49 artifacts, about 60 MB. Pulls `scala-compiler` 2.13.13, `scalameta`/`scalafmt-core` 2.13, `HikariCP` 5.1.0, `zio` 2.1.6 and `zio-json` 0.7.1 onto the **runtime** classpath. |

Both libraries are built with Scala 3.3.x, so Scala 3.9 can read them. All tests ran on Scala 3.9.0 and Temurin 21.0.13.

## Results per criterion

| # | Criterion | A Magnum + own wrapper | B ZIO Quill | C Plain JDBC |
|---|---|---|---|---|
| 1 | `SET LOCAL` tenant, RLS, no pool leak | ✅ `set_config('app.tenant_id', ?, true)` as the first statement in `transact`. A sees only A. An insert for B is rejected. No tenant = error. Same backend pid in the next transaction shows `''`. | ✅ Same SQL through `sql"...".as[Query[String]]`, prepended in the wrapper (Quill has no hook for transaction start). Same results. | ✅ Same |
| 2 | `FOR UPDATE SKIP LOCKED`, 2 fibers | ✅ One `UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED) RETURNING …`. 40 jobs, 40 distinct claims, both workers busy. | ✅ Needs the infix `sql"${query[Job]…} FOR UPDATE SKIP LOCKED".as[Query[Job]]` plus a second `UPDATE` statement. Same result. | ✅ (plain SQL) |
| 3 | JSONB write/read, `WHERE x->>? = ?` | ✅ Own `DbCodec[Jsonb]` with 10 lines (`PGobject`). Filter is plain SQL. | ✅ Built in: `JsonbValue[zio.json.ast.Json]`. The filter needs the infix `sql"${d.metadata}->>${lift(key)}".as[String]`. | ✅ Manual `PGobject` |
| 4 | Append-only audit, typed ZIO error | ✅ `UPDATE` gives `AppendOnlyViolation` (P0001, trigger). `DELETE` gives `PermissionDenied` (42501, no grant). The row survives. | ✅ Same mapping. Only after tunnelling, because `ctx.transaction` requires `ZIO[R, Throwable, A]`. | ✅ |
| 4b | Failing **COMMIT** (deferred constraint) | ✅ Typed `DbError.Other("23505", …)`, because the wrapper commits in the main flow. | ❌ **Defect** (`ZIO.die`). Quill commits inside `ZIO.succeed(connection.commit())` in a finalizer, so `mapError` never sees it. | ✅ (own wrapper) |
| 5 | Give the same `Connection` to another component | ✅ `DbTx.connection` is public. `MagnumDb.withConnection(f)` runs on it. Rollback and commit cover both writes, and the callback sees the tenant GUC. | ✅ Via `ctx.dsDelegate.currentConnection` (a public `FiberRef[Option[Connection]]`, but internal-looking API). Same results. | ✅ |
| 5b | **Flowable requirement:** one connection per tx in a FiberRef, owned outside; can the library *expose* it, and can it *run inside* a tx whose connection comes from outside? | ✅ **Expose:** yes (`DbTx.connection`). **Join:** yes. `MagnumBridge.dbTx(con)` wraps any `Connection`, so `MagnumDb.joined(...)` reads `SharedTx.ref`. Magnum never commits or closes it. Test: same `pg_backend_pid` for Flowable and Magnum, own insert visible, rollback removes both. | ✅ (with caveats) **Expose:** yes (`dsDelegate.currentConnection`). **Join:** yes, either by seeding Quill's own FiberRef (`currentConnection.locally(Some(con))`) or by `dsDelegate.underlying.run(q).provideEnvironment(ZEnvironment(con))`. A nested `ctx.transaction` sees the connection and does not commit. Same test passes. **But** there are now two FiberRefs (ours and Quill's) to keep in sync, and the reach goes into `dsDelegate`. | ✅ trivial |
| 6 | Clean compile, 10 repositories (5 queries each) | **9.3 s** (9.1 / 9.4 / 9.4) | **24.5 s** (24.3 / 24.4 / 24.9) | **6.5 s** (6.4 / 6.6 / 6.5) |
| 6 | Readability | Plain SQL in an interpolator. `Repo[Creator, E, Id]` covers CRUD. Easy to review against `EXPLAIN`. | The DSL is nice for simple CRUD. Every query prints the SQL it generates at compile time (useful, but noisy: one `Info` per query). The interesting queries are infix strings anyway. Column aliases are generated (`tenant_id AS tenantId`). | Verbose: a manual `ResultSet` reader for every entity. |
| 7 | Testability with ZIO Test | ✅ 13 tests against real PG, about 1 s. Repositories are plain functions `(using DbCon)`, easy to call. | ✅ 12 tests, about 1 s. Contexts can be built per test (`new Quill.Postgres(SnakeCase, ds)`). | ✅ |

Measurement notes:

- Criterion 6 uses `bench/measure.sh`. Each candidate gets 10 generated repositories (insert, byId, byStatus with limit,
  JSONB filter, update) plus its wrapper/`common`. The command is `scala-cli compile --server=false` after
  `rm -rf .scala-build`, with dependencies already cached, on the same machine, 3 runs each. A trivial project with ZIO
  only takes about 1.7 s, so the cold compiler start is a large part of the JDBC number. **Net over the JDBC baseline:
  Magnum about +2.8 s, Quill about +18 s for 50 queries** (about 0.35 s per Quill query). Quill reports 4–176 ms per
  query in its own macro log. The rest is typer/inlining overhead.
- Test run times (excluding compile): Magnum 0.9–1.4 s, Quill about 1.0–1.2 s, JDBC about 0.6 s.

## Problems encountered (exact messages)

1. **`SET LOCAL` cannot take a bind parameter** (all candidates, PostgreSQL itself):
   `ERROR: syntax error at or near "$1"` (SQLSTATE 42601). Use `select set_config('app.tenant_id', ?, true)` instead.
   There is a test for this in A and B.
2. **The RLS policy from the task fails loudly when no tenant is set**, which is the fail-closed behaviour we want:
   - On a connection that never had the GUC set: `ERROR: unrecognized configuration parameter "app.tenant_id"` (42704).
   - On a pooled connection whose previous transaction used `SET LOCAL`: the GUC is reset to `''`, so
     `ERROR: invalid input syntax for type uuid: ""` (22P02).

   Both are proof of "no leak". For the main schema, decide whether this should be an error (current policy) or return
   zero rows (`nullif(current_setting('app.tenant_id', true), '')::uuid`). An error is easier to spot when a tenant is
   missing by mistake.
3. **RLS insert for a foreign tenant:** `ERROR: new row violates row-level security policy for table "document"` has
   SQLSTATE **42501**, the same as `permission denied for table audit_event`. To tell them apart, `DbError` has to
   look at the message as well, or the policy has to get a separate `WITH CHECK`.
4. **Magnum: `DbTx`/`DbCon` constructors are `private[magnum]`** in 1.3.1. To manage the transaction as a ZIO scoped
   resource, or to join an external connection, we need `magnum/MagnumBridge.scala` (`package com.augustnagro.magnum`;
   `DbTx(con, SqlLogger.Default)`). It compiles fine (package-private access works across compilation units), but it is
   a hack that could break in 2.x. The alternative without the hack is Magnum's own `transact(dataSource)`, run inside
   `ZIO.attemptBlocking` ("variant 2", tested). That variant cannot join an external connection, and the transaction
   body cannot contain ZIO effects.
5. **Magnum: a `case class` wrapper for JSONB was silently encoded as `varchar`:**
   `ERROR: column "payload" is of type jsonb but expression is of type character varying` (42804). With
   `case class Jsonb(value: String)` plus a given `DbCodec[Jsonb]` in its companion, the `sql"..."` macro picked
   `DbCodec.derived` (`inline given derived[E: Mirror.Of]`, which calls `setString`) instead of our codec. The
   `Repo`-based insert used our codec correctly. Fix: `opaque type Jsonb = String`. Lesson: wrapper types for DB
   values should be opaque types (or classes without a `Mirror`). Check this in code review.
6. **Magnum: naming clash in ZIO Test:** a helper called `run` collides with `ZIOSpecAbstract.run`
   (`method run in class ZIOSpecAbstract does not take parameters`). The helper was renamed to `inTx`.
7. **Quill: `ctx.transaction` only accepts `ZIO[R, Throwable, A]`:**
   `Found: zio.ZIO[R, spike.common.DbError, A]  Required: zio.ZIO[Nothing, Throwable, Any]`. A non-Throwable error ADT has
   to be tunnelled through an exception (`Tunnel(error)`) and unwrapped again.
8. **Quill: a failing COMMIT is a defect, not a failure.** In `ZioJdbcContext.transaction`:
   `case Success(_) => blocking(ZIO.succeed(connection.commit()))`. With a deferred unique constraint, the
   `duplicate key value violates unique constraint "deferred_check_k_unique"` ends up in `Cause.die`. Our wrapper never
   sees it. This also matters for serialization failures (40001) if we ever use `SERIALIZABLE`.
9. **Quill: `Quill.Postgres(naming, ds)` (the `apply` method) returns `PostgresLite`, which has no JSON encoders.**
   Use `new Quill.Postgres(...)` or `Quill.Postgres.fromNamingStrategy`.
10. **Quill dependency footprint:** `scala-compiler`/`scala-reflect` 2.13.13, `scalameta` 4.9.3 and `scalafmt-core`
    3.8.1 (all `_2.13`) end up on the runtime classpath. It also brings an older `HikariCP` 5.1.0 and `zio` 2.1.6
    (evicted by ours). This is a risk of `_2.13`/`_3` conflicts in the sbt build.
11. **Locale:** the PostgreSQL driver localises server messages (`Wobei:` instead of `Where:`). The projects set
    `-Duser.language=en`. Assertions match message prefixes.
12. scala-cli 1.8.0 + Bloop + Scala 3.9.0: use `--server=false` (see the Flowable spike).

Common to both libraries (by design, not a bug): a transaction or connection held in the ZIO environment or a FiberRef
is **inherited by forked fibers**. `zipPar` inside one transaction would use the same JDBC connection from two threads.
Rule for the main code: no parallelism inside a transaction.

## Recommendation

**Use Magnum 1.3.1 (core only) with our own thin ZIO wrapper.** Do not use `magnumzio` (milestone only), `magnumpg`
(nothing we need in 1.3.1), or ZIO Quill. Update ADR 0012 to "Accepted".

Rationale: every criterion passes. The hard parts of this platform (RLS via `set_config`, `SKIP LOCKED`, JSONB
operators, append-only audit, the shared Flowable transaction) are plain SQL, and Magnum keeps them plain. Owning the
transaction ourselves is not extra work. The Flowable spike requires it anyway, because the connection must live in our
`FiberRef` so that Flowable joins it via `transactionsExternallyManaged=true`. Magnum fits this model directly
(`DbTx` = wrapper around that one connection). Quill would need its private connection FiberRef synchronised with ours.

### Consequences

Positive:
- SQL stays readable and reviewable. There is no generated SQL, and `EXPLAIN` can be used directly.
- Compile time is close to plain JDBC (about +0.06 s per query versus about +0.35 s for Quill).
- Dependencies are small (one artifact plus the scala-library that Scala 3 brings anyway), with no conflicts with our stack.
- One transaction model for our code and Flowable: one connection, one FiberRef, commit/rollback in one place, and
  commit errors as typed failures.
- Typed errors (`DbError`) are under our control.

Negative / cost:
- We maintain the wrapper ourselves (about 100–150 lines once production-ready: transaction, tenant, error mapping, logging, metrics).
- The bridge into `com.augustnagro.magnum` for `DbTx` (problem 4) has to be re-checked on each Magnum upgrade. Long
  term, we could ask upstream for a public `DbTx` constructor, or move to Magnum 2.x when it is stable.
- There is no compile-time check of SQL against the schema. That is covered by Testcontainers integration tests
  (repository tests are cheap, see criterion 7).
- Magnum is a smaller project (one main maintainer). Our risk is low because the API surface we use is tiny
  (`sql"..."`, `DbCodec`, `Repo`), and we could replace it with plain JDBC (variant C) in one module at a time.
- JSONB needs our own codec (10 lines), or a zio-json-based codec if we want typed payloads.

### What the main code base needs

`project/Dependencies.scala`:

```scala
val magnum = "1.3.1"
val database = Seq(
  "com.augustnagro" %% "magnum" % Versions.magnum,
  // + existing postgresql, flyway, HikariCP
)
```

Wrapper sketch. The single owner of the transaction is the FiberRef from the Flowable spike (`Tx`/`ExternalTx`);
Magnum only joins it:

```scala
// db-core: package com.augustnagro.magnum (bridge, 3 lines)
object MagnumBridge:
  def dbTx(con: java.sql.Connection): DbTx = DbTx(con, SqlLogger.Default)

// db-core: our API
object Db:
  /** One connection, autoCommit=false, set_config('app.tenant_id', ?, true), kept in Tx.ref (FiberRef);
    * Flowable joins via BoundDataSource/ExternalTransactionContextFactory. Commit in the main flow
    * (typed DbError), rollback in the finalizer, listeners fired by ExternalTx. */
  def transact[R, E, A](tenant: TenantId)(zio: ZIO[R, E, A]): ZIO[R, E | DbError, A]

  /** Runs Magnum code on the current transaction's connection, on the blocking pool. */
  def sql[A](f: DbTx ?=> A): IO[DbError, A] =
    Tx.current.someOrFail(DbError.NoTransaction).flatMap(tx =>
      ZIO.attemptBlocking(ExternalTx.withBound(tx)(f(using MagnumBridge.dbTx(tx.connection)))).mapError(DbError.from))

// repositories: plain functions
object DocumentRepo:
  def byMetadata(key: String, value: String)(using DbCon): Vector[Document] =
    sql"select id, tenant_id, title, metadata from document where metadata->>$key = $value".query[Document].run()
```

Also needed:
- An `opaque type Jsonb` (or a zio-json-based `DbCodec[A]`) for JSONB.
- `DbError.from` mapping by SQLSTATE. Separate RLS violations from missing grants.
- A test layer with Testcontainers and the Flyway migrations.
- A rule for code review: no parallel fibers inside `Db.transact`.
- An app DB role without `BYPASSRLS`, separate from the migration owner. Grant only `SELECT, INSERT` on
  `dms.audit_event`.

## Open risks

- The `private[magnum]` bridge (problem 4) might break with Magnum 2.x. Watch the 2.0 final release, which might also
  make `magnumzio` usable.
- The policy behaviour when no tenant is set (an error versus zero rows, problem 2) still needs a decision in the RLS ADR (0014).
- The 42501 ambiguity between an RLS violation and a missing grant (problem 3).
- The compile-time numbers come from scala-cli without a server (cold compiler). Absolute times in sbt with a warm
  Zinc/Bloop will be lower, but the ratio between the candidates should hold.

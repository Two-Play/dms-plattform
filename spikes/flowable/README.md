# Spike DMS-22: Flowable embedded in Scala 3 + ZIO 2

Time-boxed technical spike for [ADR 0006](../../docs/adr/0006-flowable-workflow.md). Minimal code, not production quality.
Flowable only runs configurable review/approval flows; the document status stays in the pure state machine
`modules/documents/api/src/main/scala/dms/documents/Lifecycle.scala`.

## TL;DR

| Topic | Result |
|---|---|
| Version | **Use 8.0.0.** Same Java 17 baseline as 7.2.0, same API for everything we use (identical spike code compiles and passes on both), no Spring Boot, newer Spring 7 / Jackson 3. Exclude `commons-logging` (duplicate classes with `jcl-over-slf4j`). |
| Engine without Spring | `StandaloneProcessEngineConfiguration` + HikariCP works. 32 tables (IDM + event registry disabled; 45 with them), in their own PostgreSQL schema `flowable`. Boot: 1.4–3.9 s incl. DDL on an empty DB, ~1.5–2.3 s in a fresh JVM with existing schema (mostly class loading), ~22 MB heap. |
| BPMN | submit, then parallel review (2 candidate groups), then approval with an interrupting boundary timer that escalates to `approval-managers`. Runs end to end. Timer fired 100–570 ms after its due date. |
| ZIO | `WorkflowService` trait, every engine call in `ZIO.attemptBlocking`, typed `WorkflowError`. |
| Transactions | **A shared transaction works.** Own JDBC write + `completeTask` commit or roll back together (5 scenarios proven). Needs about 170 lines of glue: a custom transaction interceptor, a thread-bound `DataSource`, a custom `TransactionContextFactory`, and `transactionsExternallyManaged=true`. Default standalone mode demonstrably produces inconsistent state. |
| Versioning | A v1 instance stays on v1 after v2 is deployed. New starts use v2. |
| Correlation | Business key `"<documentId>:<version>"` plus process variables `documentId`, `documentVersion`. Tenant via Flowable `tenantId`. |

## Run

Prerequisites: Java 21 via coursier, scala-cli, Docker container `dms-plattform-db-1` (PostgreSQL 17 on `localhost:5433`).

```bash
cd spikes/flowable
./run.sh 8.0.0      # or ./run.sh 7.2.0; drops + recreates database spike_flowable, writes run-<version>.log
SPIKE_FULL_ENGINE=1 ./run.sh 8.0.0   # also boot IDM engine and event registry (45 tables)
```

`run.sh` uses `scala-cli run --server=false`. Bloop failed on this machine with
`sbt.internal.inc.InvalidComponent: Expected single file for component 'org.scala-lang-scala3-sbt-bridge-3.9.0-bin_3.9.0__65.0'`, probably because the
shared bridge cache was being compiled in parallel. The Flowable version is passed with `--dep`, so the same sources are
compiled against both versions. The scenarios assume a fresh database, and `run.sh` takes care of that.

Files:

| File | Purpose |
|---|---|
| `src/project.scala` | scala-cli directives (Scala 3.9.0, ZIO 2.1.27, PostgreSQL 42.7.14, HikariCP 7.1.0, slf4j-simple) |
| `src/Engine.scala` | HikariCP + `StandaloneProcessEngineConfiguration` setup |
| `src/ExternalTx.scala` | Shared-transaction glue (Flowable side) |
| `src/Tx.scala` | Shared-transaction glue (ZIO side: `Tx.transact`, `Tx.blocking`) |
| `src/WorkflowService.scala` | ZIO service trait + Flowable implementation, typed errors |
| `src/Main.scala` | Scenarios for Q3 to Q7 with log output |
| `src/resources/processes/document-review-v{1,2}.bpmn20.xml` | BPMN models. v2 adds a third parallel review. |

## Evidence (run with 8.0.0; 7.2.0 output is identical apart from ids and timings)

```
[02:27:45.579] Flowable version on classpath: 8.0.0, Java 21.0.13
[02:27:47.580] [boot #1] engine 'shared-tx' built in 1946 ms (sharedTx=true)
[02:27:47.598]   tables in schema 'flowable': before=0 after=32; List(common.schema.version=8.0.0.0, schema.version=8.0.0.0)
[02:27:48.140] [boot #2 (schema exists)] engine 'shared-tx' built in 531 ms (sharedTx=true)
[02:27:48.331] deployed v1: 2a8534ff-d277-11f1-b77a-2cf05d053250
[02:27:48.343] re-deploy of identical v1 (duplicate filtering) returns existing deployment: 2a8534ff-d277-11f1-b77a-2cf05d053250
[02:27:48.344] === Q3/Q7: end-to-end run with boundary timer escalation
[02:27:48.479]   started ProcessRef(2aa60372-…,documentReview:1:2a9c1861-…,1) for DocumentRef(d1b94694-4c84-46f6-b391-e10a19638854,3) (businessKey=d1b94694-4c84-46f6-b391-e10a19638854:3)
[02:27:48.547]   tasks for document: List(reviewLegal[reviewers-legal] doc=Some(DocumentRef(d1b94694-…,3)), reviewQuality[reviewers-quality] doc=Some(DocumentRef(d1b94694-…,3)))
[02:27:48.566]   lena (reviewers-legal) sees: List(Legal review)
[02:27:48.584]   quinn tries to complete legal review -> typed error: NotCandidate(2aaabe70-…,quinn)
[02:27:48.597]   lena completes 'Legal review' (2aaabe70-…)
[02:27:48.701]   quinn completes 'Quality review' (2aab0c93-…)
[02:27:48.897]   alex (approvers) sees: List(Approve document); timer jobs: 1
[02:27:48.904]   timer job due at 2026-10-28T02:27:51.812Z
[02:27:52.453]   TIMER FIRED: escalation task List(Approve document (escalated)) for approval-managers created at 2026-10-28T02:27:52.274Z (462 ms after due date; seen after 3528 ms of polling)
[02:27:52.462]   original approval task still open: false
[02:27:52.480]   maria completes 'Approve document (escalated)' (2cfa53d9-…)
[02:27:52.711]   process ended: true, endActivity=done, startUser=author-anna
[02:27:52.716]   activity history: submitted -> f1 -> forkReview -> f2 -> reviewLegal -> f3 -> reviewQuality -> f4 -> joinReview -> f5 -> joinReview -> f6 -> approve -> approvalOverdue -> f7 -> approveEscalated -> f9 -> done
[02:27:52.717] === Q5: shared transaction (own JDBC write + Flowable completeTask)
[02:27:52.899]   (a) write + completeTask + business failure -> Left(business rule violated); rows=0, open tasks=List(reviewLegal, reviewQuality)
[02:27:52.917]   (b) write + completeTask(unknown id) -> Left(TaskNotFound(no-such-task)); rows=0
[02:27:52.935]   (c) write + Flowable FlowableObjectNotFoundException -> Left(org.flowable.common.engine.api.FlowableObjectNotFoundException: execution no-such-execution doesn't exist); rows=0
[02:27:52.952]   (e) write + swallowed Flowable exception -> Left(RolledBack(transaction was marked rollback-only by Flowable)); rows=0
[02:27:53.066]   (d) write + completeTask -> Right(()); rows=1, open tasks=List(reviewQuality)
[02:27:53.067] === Q6: deploy v2 while a v1 instance runs
[02:27:53.103]   v1 instance: ProcessRef(2d748d0e-…,documentReview:1:2a9c1861-…,1)
[02:27:53.195]   new instance after v2 deploy: ProcessRef(2d815e64-…,documentReview:2:2d7e2a13-…,2)
[02:27:53.237]   v1 instance tasks: List(reviewLegal, reviewQuality); v2 instance tasks: List(reviewLegal, reviewQuality, reviewSecurity)
[02:27:53.279]   lena completes 'Legal review' (2d75024c-…)
[02:27:53.408]   quinn completes 'Quality review' (2d75295f-…)
[02:27:53.485]   v1 instance after 2 reviews (no security review required): List(approve)
[02:27:53.493]   v1 instance still bound to definition documentReview:1:2a9c1861-… (version 1)
[02:27:53.532] heap used after GC with running engine: 22 MB; JVM threads: 32
[02:27:54.025] === Q5 baseline: default StandaloneProcessEngineConfiguration (no shared tx)
[02:27:54.129]   write + completeTask + business failure -> Left(business rule violated); rows=0, open tasks=List(reviewQuality, reviewSecurity)  <-- INCONSISTENT: task completed, row rolled back
[02:27:54.132] DONE
```

(UUIDs shortened with `…`. The baseline shows `reviewSecurity` because v2 is the latest definition at that point.)

## Q1: Version 7.2.0 vs 8.0.0

Measured with `cs resolve org.flowable:flowable-engine:<v> | wc -l` and `cs fetch … | xargs du -cb`:

| | 7.2.0 (Maven Central: 2025-08-21) | 8.0.0 (Maven Central: 2026-02-27) |
|---|---|---|
| Bytecode baseline | class major 61, which means **Java 17** (built with JDK 21) | class major 61, which means **Java 17** (built with JDK 21) |
| Artifacts (transitive) | 56 | 57 |
| Total jar size | 15,882,839 bytes (15.9 MB) | 16,325,518 bytes (16.3 MB) |
| Spring | `spring-core`, `spring-beans`, `spring-jcl` **6.2.10** | `spring-core`, `spring-beans` **7.0.3** (+ `commons-logging` 1.3.5, `jspecify` 1.0.0) |
| Spring Boot / spring-tx / spring-jdbc | none | none |
| Jackson | `com.fasterxml.jackson` **2.19.2** | **Jackson 3** `tools.jackson.core` 3.0.1 (+ `jackson-annotations` 2.20, which Jackson 3 reuses) |
| MyBatis | 3.5.19 | 3.5.19 |
| Jakarta | `jakarta.mail-api` 2.1.3, `jakarta.activation-api` 2.1.3, Angus | same |
| Other | commons-lang3/io/codec, joda-time, java-uuid-generator, slf4j 2.0.17 + `jcl-over-slf4j` | same (newer patch versions) |
| DB schema version | `7.2.0.2` | `8.0.0.0` |
| Spike code changes needed | none | none (identical sources) |

Conflicts with our stack (`project/Dependencies.scala`):
- ZIO, tapir/zio-json, nimbus-jose-jwt and HikariCP share no transitive dependencies with Flowable. Flyway 13.11.1 only brings
  `jackson-annotations` 2.22. With 7.2.0 that gives annotations 2.22 next to databind 2.19.2, a version mix (works, but untidy).
  With 8.0.0 it fits Jackson 3 (annotations 2.x by design).
- slf4j 2.0.17 matches `zio-logging-slf4j2` / logback 1.6.
- **8.0.0 only:** `commons-logging:commons-logging:1.3.5` (from spring-core 7) and `org.slf4j:jcl-over-slf4j:2.0.17` (from
  flowable) both contain `org/apache/commons/logging/LogFactory.class`, so there are duplicate classes on the classpath. Exclude
  `commons-logging` (or `jcl-over-slf4j`; commons-logging 1.3 detects slf4j by itself). sbt-native-packager/assembly
  would otherwise pick one at random.
- Both versions bring Jakarta Mail / Angus and Spring core even if unused. Small, but present. Spring is used only internally
  (bean/expression utilities). Nothing needs a Spring context.

**Recommendation: 8.0.0.** Both versions run on Java 21 and behave identically for our use case. 8.0.0 is the current major
with a newer Spring and Jackson line. Spring Framework 6.2's open-source support window is shorter than 7.0's (check the
spring.io support timeline). Starting on 7.x would mean a schema plus dependency migration soon. Downside: a `.0` release, and
tooling around Flowable (modeler UI, community examples) may still target 7.x.

## Q2: Embedded engine on PostgreSQL without Spring

`Engine.build` (see `src/Engine.scala`):
- `StandaloneProcessEngineConfiguration` with a HikariCP `DataSource` (pool 10). `setDatabaseSchemaUpdate("true")` creates or
  upgrades the schema on boot. Production should use `"false"` and run the Flowable DDL scripts
  (`org/flowable/db/create/flowable.postgres.*.sql` in the jars) through Flyway, or keep `"true"` with
  `useLockForDatabaseSchemaUpdate` for multi-node.
- Own schema: Hikari `schema=flowable` sets `search_path` (Flowable SQL is unqualified), plus `setDatabaseSchema("flowable")`
  for the metadata check. Our tables stay schema-qualified (`public.review_decision`). Result: all Flowable tables in schema `flowable`,
  `public` untouched. This answers the ADR follow-up "own schema for Flowable tables": yes, it works.
- `setDisableIdmEngine(true)` and `setDisableEventRegistry(true)` mean we have no `ACT_ID_*` (9) or `FLW_EVENT*`/`FLW_CHANNEL*` (4)
  tables. Users and groups come from our identity module and are passed as candidate groups.
  - 32 tables: `ACT_RU_*` 13, `ACT_HI_*` 10, `ACT_RE_*` 3, `ACT_GE_*` 2, `ACT_EVT_LOG`, `ACT_PROCDEF_INFO`, `FLW_RU_*` 2. 2.8 MB on an empty DB.
  - 45 tables with `SPIKE_FULL_ENGINE=1`.
- `StrongUuidGenerator` instead of the default `DbIdGenerator`. This avoids extra `REQUIRES_NEW` transactions for id blocks.
- Async executor activated. Acquire wait times are lowered to 500 ms (default 10 s) via `getAsyncExecutorConfiguration`, because the
  older `setAsyncExecutorDefault…WaitTime` setters are deprecated.
- Startup (wall clock around `buildProcessEngine()`, several runs; first engine in the JVM):
  - empty DB (DDL): 1.4–3.9 s (7.2.0) / 1.4–2.8 s (8.0.0); the variance comes from this desktop machine and from JIT, so neither version is clearly faster
  - existing schema, fresh JVM: 1.5 s (7.2.0) / 2.3 s (8.0.0)
  - second engine in the same warm JVM: 0.35–0.8 s

  So boot time is dominated by class loading/MyBatis mapping parsing, not DDL.
- Footprint: ~22 MB used heap after GC with the engine running. 32 JVM threads in total, including ZIO pools and the async
  executor pool (default core 8).

## Q3: BPMN process with parallel review and escalation timer

`document-review-v1.bpmn20.xml`:

`submitted` (initiator stored), then fork, then `reviewLegal` (`reviewers-legal`) ‖ `reviewQuality` (`reviewers-quality`), then join, then `approve`
(`approvers`), then `done`. An **interrupting** boundary timer `approvalOverdue` on `approve` (`timeDuration=${approvalTimeout}`, PT3S in
the test, PT1H elsewhere) cancels the approval task and creates `approveEscalated` for `approval-managers`. That is a reassignment.
For a "reminder only" variant use `cancelActivity="false"`. That gives a parallel escalation task, and the process waits for both.

Evidence (log above): timer job due at `02:27:51.812Z`, escalation task created at `02:27:52.274Z` (462 ms later, within the
500 ms acquisition interval). The original approval task is gone. The process ends at `done`, and the activity history shows the
escalation path. Across all runs the delay was 100–570 ms.

## Q4: ZIO integration

`src/WorkflowService.scala`:

```scala
enum WorkflowError:
  case ProcessDefinitionNotFound(key: String, tenant: String)
  case TaskNotFound(taskId: String)
  case NotCandidate(taskId: String, user: String)
  case Engine(message: String, cause: Throwable)

trait WorkflowService:
  def deploy(tenant: String, resource: String): IO[WorkflowError, String]
  def startReview(tenant: String, doc: DocumentRef, initiator: String, approvalTimeout: String): IO[WorkflowError, ProcessRef]
  def listTasks(tenant: String, user: String, groups: List[String]): IO[WorkflowError, List[TaskView]]
  def tasksForDocument(tenant: String, doc: DocumentRef): IO[WorkflowError, List[TaskView]]
  def completeTask(taskId: String, user: String, groups: List[String], vars: Map[String, Any] = Map.empty): IO[WorkflowError, Unit]
```

- Every engine call goes through `Tx.blocking(f)`, which is `ZIO.attemptBlocking` and also binds the current transaction (see Q5).
  Flowable exceptions map to `WorkflowError.Engine`. Domain checks throw a private `WorkflowErrorException` inside the
  blocking block and come back as the typed error.
- Authorization uses `taskCandidateOrAssigned(user).taskCandidateGroupIn(groups)`. Groups are passed in from our identity/JWT, and
  Flowable IDM is not needed. Evidence: `quinn tries to complete legal review -> typed error: NotCandidate(…)`.
- `flowable:initiator` needs `IdentityService.setAuthenticatedUserId` (a thread-local). It is set and cleared inside the same
  blocking block, so it is safe with fiber thread hopping. Evidence: `startUser=author-anna`.
- No Flowable types in the trait. `DocumentRef`, `ProcessRef`, `TaskView` and `WorkflowError` would move to `workflow-api`.

## Q5: Transactions (own JDBC write + Flowable call)

### Options examined

| Option | Result |
|---|---|
| Default standalone (`JdbcTransactionFactory`, `StandaloneMybatisTransactionContext`) | Each Flowable command takes its own pooled connection and commits at command end. **Cannot** join our transaction. Proven inconsistent: business failure after `completeTask` rolls back our row but the task stays completed (`rows=0, open tasks=List(reviewQuality, reviewSecurity)`). |
| `transactionsExternallyManaged=true` alone | Switches MyBatis to `ManagedTransactionFactory`, so Flowable no longer commits or closes. But it still fetches a connection from the `DataSource`: with a plain Hikari pool that is a *different*, auto-commit connection (no atomicity, no rollback). It also fires `COMMITTED` listeners before anything is committed. Not sufficient on its own. |
| Shared connection bound per transaction + custom interceptor + custom `TransactionContextFactory` (what Spring's `SpringProcessEngineConfiguration` does with `DataSourceTransactionManager`) | **Works.** This is the implemented approach. |
| Outbox / idempotent compensation | Not needed for the atomicity question, because the shared transaction works. It is still the right tool for side effects outside the DB (mail, search index). |

### Implemented approach (`src/ExternalTx.scala`, `src/Tx.scala`, about 170 lines)

1. `ExternalTx` holds one JDBC connection (`autoCommit=false`), a rollback-only flag and the deferred Flowable transaction listeners.
   It is bound to the current thread via a `ThreadLocal`.
2. `BoundDataSource` is the `DataSource` given to Flowable. It returns the thread-bound connection wrapped in a proxy that ignores
   `close/commit/rollback/setAutoCommit`. When nothing is bound it returns a normal pooled connection (only for `NOT_SUPPORTED`
   schema commands).
3. `JdbcTransactionInterceptor` is returned from an overridden `createTransactionInterceptor()`; the standalone default returns
   `null`. It implements propagation:
   - `REQUIRED` joins the bound transaction or opens a new one.
   - `REQUIRES_NEW` opens a new one.
   - `NOT_SUPPORTED` unbinds.

   It wraps **every** command, including the async executor's job acquisition and execution threads. The timer in Q3 ran through it.
4. `ExternalTransactionContextFactory`: Flowable's `TransactionContext.commit()` is a no-op, and `rollback()` marks rollback-only.
   `COMMITTING`/`COMMITTED`/`ROLLED_BACK` listeners are queued and fired by `ExternalTx` around the real commit or rollback.
   This matters for the "async job created, hand it to the executor" hint, which must run only after commit.
5. `transactionsExternallyManaged=true` (`ManagedTransactionFactory`).
6. ZIO side: `Tx.transact(ds)(zio)` opens an `ExternalTx` and puts it into a `FiberRef`. `Tx.blocking` and `Tx.withConnection` bind it to
   whichever blocking-pool thread runs the step. The fiber can hop threads between steps. The transaction commits if the effect succeeds
   and rolls back on failure, defect or interruption (`acquireReleaseExitWith`).

```scala
Tx.transact(ds)(insertDecision(doc, "legal-ok") *> wf.completeTask(taskId, user, groups))
```

Proven scenarios (log above):
- (a) Write + `completeTask` + later business failure: both rolled back. Row absent, task still open.
- (b) Write + typed workflow error: write rolled back.
- (c) Write + Flowable exception (`FlowableObjectNotFoundException: execution no-such-execution doesn't exist`): write rolled back.
- (e) Flowable exception swallowed by the caller (`.ignore`): commit refused with
  `RolledBack(transaction was marked rollback-only by Flowable)`, and the write is rolled back.
- (d) Happy path: row committed and task completed.

### Consequences of the shared-transaction approach

Positive:
- Real atomicity of workflow step, lifecycle transition, and audit entry, as ADR 0001/0006 require. There is no second state
  store to reconcile, and no outbox needed for the internal consistency of workflow vs. document status.
- One pool, one database, and the async executor runs on the same mechanism.
- Same code works unchanged on 7.2.0 and 8.0.0.

Negative and risks:
- Relies on Flowable **`impl` packages** (`org.flowable.common.engine.impl.cfg.*`, `…interceptor.*`). These are stable in practice
  (Spring integration uses them), but they are not public API. Put this behind one file and cover it with an integration test.
- **Discipline:** inside `Tx.transact`, every Flowable call must go through `Tx.blocking`. A raw `engine.getTaskService.complete` in
  a separate `attemptBlocking` silently runs in its own transaction. Mitigation: only `WorkflowService` touches the engine.
- The ZIO transaction layer from the parallel DB-access spike (ADR 0012) must expose the same "one connection per transaction,
  reachable from a FiberRef" model. Otherwise Flowable cannot join. **This is the main integration constraint for 0012.**
- Long business transactions hold a connection and Flowable row locks. Concurrent completion of parallel branches can raise
  `FlowableOptimisticLockingException` at the join. That needs a retry around `Tx.transact` (not exercised in the spike).
- Flowable's process-definition **deployment cache is not transactional**. Deploy in its own transaction, never inside a business
  transaction that might roll back.
- `COMMITTED` listeners receive the already closed `CommandContext` (same as in Flowable's Spring integration). This works for the
  built-in listeners, but don't add custom ones that use it.
- Pool sizing must cover request transactions plus async executor threads.

Fallback if the glue is rejected: plain standalone engine, Flowable as source of truth for the step, then a separate transaction that
writes status + audit, guarded by an idempotency key (task id), with a reconciler job that compares `ACT_HI_TASKINST` against
document status. This is more moving parts, and documents can be temporarily inconsistent.

## Q6: Versioning

Deploying the same key again with changed content creates `documentReview:2`. The running v1 instance keeps
`processDefinitionId = documentReview:1:…`. After completing its 2 reviews it moves straight to `approve`, while the v2 instance has 3
review tasks (`reviewSecurity`). `enableDuplicateFiltering()` makes re-deploying unchanged BPMN idempotent, so it is safe to call on every
boot. Migrating running instances would be explicit (`ProcessMigrationService`) and was not part of the spike.

## Q7: Correlation of tasks with documents

- Process variables: `documentId` (UUID as string) and `documentVersion` (int). They are available on tasks via
  `includeProcessVariables()` and mapped to `TaskView.document`.
- **Business key** `"<documentId>:<version>"`. It is an indexed column (`ACT_RU_EXECUTION.BUSINESS_KEY_`), so
  "all tasks for this document version" uses `processInstanceBusinessKey(…)` instead of a variable join. Recommended as the primary
  lookup, with variables for display and expressions.
- Tenant: deployments and instances use Flowable `tenantId` (`tenant-a` in the spike). All queries filter by `taskTenantId`.
- The reverse direction (document to running process) is either the business key query or our own column
  `workflow_instance_id` on the document version, written in the same transaction as `startReview`.

## What the main code base needs

Dependencies (`project/Dependencies.scala`):

```scala
val flowable = Seq(
  ("org.flowable" % "flowable-engine" % "8.0.0")
    .exclude("commons-logging", "commons-logging") // duplicate of jcl-over-slf4j
)
```

Optionally also exclude `org.eclipse.angus`/`jakarta.mail` if mail tasks are never used. Test that first, because `flowable-mail` is
on the engine's classpath.

Module placement (as ADR 0006 requires):
- `modules/workflow/api`: `WorkflowService` trait, `WorkflowError`, `ProcessRef`, `TaskView` (uses `DocumentRef`/ids from
  `documents-api`/`kernel`). No Flowable types.
- `modules/workflow/core`:
  - `FlowableEngine` `ZLayer.scoped`, which builds the engine and closes it on release. That stops the async executor.
  - The transaction glue (`JdbcTransactionInterceptor`, `BoundDataSource`, `ExternalTransactionContextFactory`).
  - `FlowableWorkflowService`.
  - BPMN models under `src/main/resources/processes/`, deployed at startup with duplicate filtering.
  - Tenant-specific models deployed via an admin API with `tenantId`.
- The ZIO transaction primitive (`Tx.transact`/FiberRef connection) must live in the shared DB-access layer (ADR 0012) so that
  `documents-core`, `audit-core` and `workflow-core` all join the same transaction.
- Lifecycle coupling: `workflow-core` calls the Documents API inside the same `Tx.transact`. For example:
  - `startReview` + `Lifecycle.Submit`
  - completing the last review leads to `ReviewPassed`
  - approval with `approved=true` leads to `Approve` (using `Lifecycle.approve` for segregation of duties)

  Alternatively, a BPMN execution listener calls a delegate that emits the event. The delegate runs in the same transaction because
  of the glue.
- Config: `databaseSchemaUpdate=false` in production with Flowable DDL in Flyway, schema `flowable`, acquire wait times at default
  (10 s) or a few seconds, async executor pool sized against Hikari.

Service sketch for `workflow-core`:

```scala
final class FlowableWorkflowService(engine: ProcessEngine, documents: DocumentsApi) extends WorkflowService:
  def completeTask(taskId, user, groups, vars) =
    Tx.transact(
      for
        t <- checkCandidate(taskId, user, groups)                // Tx.blocking(taskQuery…)
        _ <- Tx.blocking(engine.getTaskService.complete(taskId, vars.asJava))
        _ <- lifecycleEventFor(t, vars).fold(ZIO.unit)(documents.apply(t.document, _, user)) // same transaction
        _ <- audit.record(...)                                   // same transaction
      yield ()
    )
```

## Open risks / not covered

- Row-Level Security (ADR 0014) was not tested. Flowable tables live in their own schema and should have **no** RLS policies.
  Because the shared connection carries our `SET LOCAL` tenant setting, Flowable queries on it are unaffected only if `flowable.*`
  stays without policies. Async executor connections carry no tenant context.
- Optimistic-locking retries, multi-node async executor (lock owner, expired job reset), and load or memory under many instances
  were not measured.
- `impl`-package dependency of the transaction glue needs a regression test on every Flowable upgrade.
- Schema migration path from Flowable DDL scripts into Flyway was not tried.

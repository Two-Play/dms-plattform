# 0006: Flowable as embedded workflow engine

| Field | Value |
|---|---|
| Status | Accepted (validated by spike DMS-22 on 2026-10-28) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Tenants should be able to configure review and approval processes (multi-stage approval, four-eyes principle, deadlines, escalation, parallel reviewers). The document status itself (draft, in review, approved, published, archived), on the other hand, is fixed and must be understandable without an engine. Workflow steps, status changes and audit belong in one transaction (0001).

## Decision

Flowable runs embedded in the JVM and uses the same PostgreSQL instance. The document status remains a pure Scala state machine (`modules/documents/api/src/main/scala/dms/documents/Lifecycle.scala`); Flowable controls only the configurable review and approval processes and triggers status transitions via the Documents API.

## Alternatives considered

| Criterion | Chosen: Flowable embedded | Camunda 7 | Camunda 8 | Temporal | Custom state machine |
|---|---|---|---|---|---|
| License / future | Apache-2.0, active | Community Edition discontinued | More restrictive license for production | MIT | Own |
| Operating model | In-process, same DB | In-process | Separate cluster (Zeebe) | Separate server | In-process |
| Transaction with business data | Shared transaction possible | Shared transaction possible | No | No | Yes |
| BPMN / modelled processes | Yes (BPMN, CMMN, DMN) | Yes | Yes | No, code | No |
| Configuration by tenants | Via BPMN models | Via BPMN | Via BPMN | Code only | Own editor required |
| API style | Blocking Java | Blocking Java | gRPC client | SDK, no Scala SDK | Scala/ZIO |
| Effort for deadlines, escalation, timers | Built in | Built in | Built in | Built in | Must be built |

## Consequences

### Positive

- Configurable approval processes without building a process engine in-house.
- One database, one transaction, no additional service.
- Document status remains testable and understandable independently of Flowable.

### Negative / Cost

- Flowable brings numerous tables of its own and its own thread pools (async executor) into the application.
- The blocking Java API must be wrapped with `ZIO.attemptBlocking`; transaction boundaries between Flowable and our own DB access must be clarified.
- Two state models (lifecycle and process instance) must be kept consistent.
- The shared transaction needs about 170–190 lines of glue that use Flowable `impl` packages (not public API); see spike result.

## Spike result (DMS-22, 2026-10-28)

Details, code and logs: [`spikes/flowable/README.md`](../../spikes/flowable/README.md).

| Question | Result |
|---|---|
| Version | **8.0.0.** Same Java 17 baseline and API as 7.2.0 (identical spike code passes on both); Spring 7 core/beans, Jackson 3, no Spring Boot. `commons-logging` must be excluded (duplicate classes with `jcl-over-slf4j`). |
| Engine without Spring | `StandaloneProcessEngineConfiguration` with HikariCP. Tables in their own schema **`flowable`** (32 tables with IDM and event registry disabled). Boot 1.5–3.9 s, about 22 MB heap. |
| BPMN | Parallel review by two candidate groups, approval with boundary timer escalating to `approval-managers`; runs end to end, timer fires 0.1–0.6 s after due date. |
| Shared transaction | **Works.** Own JDBC write and `completeTask` commit or roll back together (5 scenarios), via custom transaction interceptor, a data source bound to the current transaction's connection, a custom `TransactionContextFactory` and `transactionsExternallyManaged=true`; connection held in a ZIO `FiberRef`. Flowable's default mode demonstrably leaves inconsistent state. |
| Process versioning | Running instances stay on their definition version; new starts use the latest. |
| Correlation | Business key `"<documentId>:<version>"`, process variables `documentId`, `documentVersion`; Flowable `tenantId` per tenant. |

Constraints that follow from it:

- The database access layer (0012) must use **one connection per transaction, reachable from a FiberRef**, so Flowable can join.
- Inside a transaction every engine call goes through `WorkflowService`; a direct engine call silently runs in its own transaction.
- Deploy process definitions in their own transaction (the deployment cache is not transactional).
- Fallback if the glue is rejected later: standalone engine as source of truth for the step, separate idempotent transaction for status and audit, plus a reconciliation job.

## Follow-ups

- Add `flowable-engine` 8.0.0 (excluding `commons-logging`) to `workflow-core`; put the transaction glue in one file with an integration test that runs on every Flowable upgrade.
- Shared transaction primitive (`Tx.transact` with FiberRef connection) belongs in the database access layer (0012) so documents, audit and workflow join the same transaction.
- Flowable DDL into Flyway with `databaseSchemaUpdate=false` in production.
- Not yet covered: row-level security (0014) next to schema `flowable` (which must have no policies), retries on `FlowableOptimisticLockingException` at the review join, async executor on several nodes.
- Encapsulation exclusively in the `workflow-core` module; no Flowable types in `workflow-api`.
- Ruled out: modelling the document status in BPMN; a separate workflow cluster.

## Addendum (DMS-40, 2026-10-28): implementation deviations

Implemented as described in ADR 0024. Small deviations from the spike, all within this decision:

- Flowable's SQL is qualified with the table prefix `flowable.` (`tablePrefixIsSchema=true`) instead of a pool whose search path is `flowable`, so the engine joins the application's `Db.transact` connection (role `dms_app_user`) without changing its search path. Only schema creation uses connections with search path `flowable`.
- Flowable's DDL is not copied into Flyway: `dms.app.Migrate` runs `FlowableEngine.migrateSchema` as schema owner after Flyway (`databaseSchemaUpdate=true` only there, `false` at runtime). Migration V11 creates the schema and default privileges for `dms_app`. Upgrades of Flowable therefore go through `Migrate` as well.
- The shared transaction is joined per engine call (`SharedTransaction.joined`) because `Db.transact` owns commit and rollback; Flowable's transaction listeners fire at the end of each engine call. The async executor stays off until timers are needed; then listener firing must move to a commit hook in `Db`.
- An engine exception is returned as a typed `WorkflowError` instead of marking the whole transaction rollback-only (`Db` has no such flag); a failed Flowable command flushes nothing.
- Row-level security next to schema `flowable`: confirmed working, `flowable.*` has no policies (tested).

## Reversibility

**medium**: Thanks to the encapsulation in `workflow-core` and the separate lifecycle state machine, a replacement remains limited to one module. Running process instances and tenant-specific BPMN models would, however, have to be migrated or remodelled.

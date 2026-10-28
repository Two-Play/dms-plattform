# 0006: Flowable as embedded workflow engine

| Field | Value |
|---|---|
| Status | Accepted (integration to be validated by a spike) |
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
- Version choice open: Flowable `8.0.0` and `7.2.0` are available.

## Follow-ups

- Spike: Flowable version (8.0.0 vs. 7.2.0), shared transaction with our own JDBC access (0012), tenant isolation via Flowable `tenantId`, behaviour under row-level security (0014), startup time and memory footprint.
- Encapsulation exclusively in the `workflow-core` module; no Flowable types in `workflow-api`.
- Decide whether Flowable tables live in their own schema.
- Ruled out: modelling the document status in BPMN; a separate workflow cluster.

## Reversibility

**medium**: Thanks to the encapsulation in `workflow-core` and the separate lifecycle state machine, a replacement remains limited to one module. Running process instances and tenant-specific BPMN models would, however, have to be migrated or remodelled.

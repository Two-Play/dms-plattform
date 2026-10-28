# 0001: Modular monolith with asynchronous workers

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The platform covers templates, documents, approval workflows, versioning, rendering, archiving, search, audit and notifications. A workflow step, a new document version and an audit entry must succeed together or fail together. Development is done by one person with AI assistance. Compute-heavy tasks (PDF rendering, OCR, indexing, bulk generation) must not block requests.

## Decision

The platform is built as a modular monolith in a single deployment. Each business module is a pair of sbt subprojects `<module>-api` and `<module>-core`; modules depend only on `kernel` and on other modules' `*-api`, and only `app` wires the `*-core` modules together. Rendering, OCR, indexing and bulk generation run in asynchronous workers that fetch jobs from the database (see 0010).

## Alternatives considered

| Criterion | Chosen: Modular monolith + workers | Microservices | Classic layered monolith |
|---|---|---|---|
| Transaction workflow + version + audit | One local DB transaction | Distributed transaction or saga required | One local DB transaction |
| Enforcement of module boundaries | Compiler (sbt dependencies) | Network boundary | Convention only, erodes in practice |
| Operational effort | One deployment plus worker processes | Many deployments, service discovery, distributed tracing | One deployment |
| Suitability for solo development | High | Low | High |
| Independent scaling | Only workers can be scaled separately | Per service | None |
| Later extraction of a module | Possible via existing `*-api` | Already separate | Costly, boundaries must be created first |
| Refactoring across module boundaries | One compiler run, IDE refactoring | Versioned interfaces, coordination | Easy, but without protection |

## Consequences

### Positive

- Business consistency via ACID transactions instead of compensation logic.
- Illegal dependencies (e.g. `documents-core` → `workflow-core`) fail at compile time.
- One build, one deployment, one log stream: low operational effort.
- Compute load is decoupled from the request path; workers scale independently.

### Negative / Cost

- Twice the number of subprojects (api/core) and therefore more build configuration.
- All modules share the JVM, heap and release cycle; a faulty module can affect the whole application.
- A shared database requires discipline: each table belongs to exactly one module, other modules access it only through that module's `*-api`.
- Asynchronous workers bring eventual consistency for renderings and the search index.

## Follow-ups

- The dependency rule is commented in `build.sbt` and must be followed for every new module.
- In addition, an architecture test or review checklist: no imports from other modules' `*-core` packages.
- Document table ownership per module in Flyway migrations (prefix or schema per module).
- Define the worker protocol (job table, retries, idempotency) according to 0010.
- Ruled out: synchronous network calls between our own modules; separate databases per module.

## Reversibility

**medium**: Thanks to the `*-api` split, a module can later be extracted as a separate service by exposing its API via HTTP or messaging. The effort lies in transactions that currently span modules and would then have to be replaced by outbox and sagas.

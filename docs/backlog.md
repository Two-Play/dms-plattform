# Backlog and sprint plan

As of 2026-10-28 · Source of the business stories: [business concept (Fachkonzept)](fachkonzept.html), chapter 12. Jira project: **DMS** (it-heimnet.atlassian.net).

## Planning assumptions

| Assumption | Value | Consequence |
|---|---|---|
| Team | Solo (Philippe + AI assistance) | Hardly any parallel work; the order strictly follows the dependencies |
| Sprint length | 2 weeks, starting Monday | Review/planning on Friday/Monday respectively |
| Capacity | 16 story points per sprint, reduced on public holidays | Rough value; readjust after sprint 2 based on the actual velocity |
| Estimation scale | Fibonacci 1, 2, 3, 5, 8 | Anything above 8 is split |
| MVP scope | All MVP stories of the business concept plus technical enablers | Result: 12 sprints (0–11), MVP completion 2027-04-23 |

**Deviation from the business concept:** The business concept estimated 8–10 sprints for the MVP. With the necessary technical enablers (login, permissions, basic templates, version model) the total is 177 SP, i.e. 12 sprints. **US-11.1 (group template with inheritance) was moved to R2**, because otherwise it would not fit into sprint 11 and the MVP is usable with templates at tenant level as well. Consequence: in the MVP, groups of companies must copy templates per company.

## Sprints

| Sprint | Period | Cap. | Goal |
|---|---|---|---|
| Sprint 0 | 2026-11-02 – 2026-11-13 | 16 | Foundation: build, ADRs, local environment, CI, database and workflow spikes |
| Sprint 1 | 2026-11-16 – 2026-11-27 | 16 | Login via ZITADEL, database foundation, frontend scaffold |
| Sprint 2 | 2026-11-30 – 2026-12-11 | 16 | Permissions, audit trail, file storage |
| Sprint 3 | 2026-12-14 – 2026-12-23 | 12 | Document classes, metadata, outbox (shortened due to holidays) |
| Break | 2026-12-24 – 2027-01-03 | – | – |
| Sprint 4 | 2027-01-04 – 2027-01-15 | 16 | Templates with placeholders, conditions, locked sections |
| Sprint 5 | 2027-01-18 – 2027-01-29 | 16 | Document from template, preview, version model |
| Sprint 6 | 2027-02-01 – 2027-02-12 | 16 | Approval workflow with segregation of duties |
| Sprint 7 | 2027-02-15 – 2027-02-26 | 16 | PDF/A rendition, publication, minor versions |
| Sprint 8 | 2027-03-01 – 2027-03-12 | 16 | Acknowledgement, tasks, delegation, impact analysis |
| Sprint 9 | 2027-03-15 – 2027-03-26 | 14 | Search (full text, reference date, permission filter); Good Friday |
| Sprint 10 | 2027-03-29 – 2027-04-09 | 14 | Version comparison, review cycle, legal hold; Easter Monday |
| Sprint 11 | 2027-04-12 – 2027-04-23 | 16 | Evidence report, intranet interface, compliance cockpit, MVP acceptance |

## Conventions in Jira

- **Labels:** `release-mvp` or `release-r2`; `modul-<name>` (kernel, identity, authz, templates, documents, workflow, rendering, archive, search, audit, notifications, integration, app, frontend, infra); `typ-spike` for spikes; `klaerung` when a decision is open; `fk-US-xx.y` for stories from the business concept.
- **Story points:** field "Story point estimate".
- **Epics:** start date and due date = first/last sprint with MVP content.
- **Status:** All issues start in "Zu erledigen" (To Do). Completed work is set to "Erledigt" (Done) only after review.

## Epics

| ID | Epic | Modules |
|---|---|---|
| EP-00 | Technical foundation | infra, app |
| EP-01 | Templates and building blocks | templates |
| EP-02 | Metadata and classification | documents |
| EP-03 | Document creation and filling | documents, templates |
| EP-04 | Workflow and approval | workflow |
| EP-05 | Versioning and change tracking | documents |
| EP-06 | Rendering and publication | rendering |
| EP-07 | Review and recertification | documents, notifications |
| EP-08 | Archiving and retention | archive |
| EP-09 | Search and navigation | search |
| EP-10 | Tasks and notifications | notifications, workflow |
| EP-11 | Permissions and tenants | identity, authz |
| EP-12 | Audit and compliance | audit |
| EP-13 | Multilingualism | templates, documents |
| EP-14 | Integration and automation | integration |
| EP-15 | Reporting and dashboards | app, frontend |

## Issues

Columns: ID (planning ID, used as a prefix in square brackets in the Jira title), type, epic, title, SP, release, sprint, modules. Where `Source = FK` (FK = Fachkonzept, the business concept), the user story and acceptance criteria are stated verbatim in chapter 12 of the business concept; for all others the description is below.

| ID | Type | Epic | Title | SP | Release | Sprint | Modules | Source |
|---|---|---|---|---|---|---|---|---|
| T-00.1 | Task | EP-00 | Project scaffold: sbt multi-module, formatting, domain kernel | 3 | MVP | 0 | infra, kernel | below |
| T-00.2 | Task | EP-00 | Document architecture decisions as ADRs | 2 | MVP | 0 | infra | below |
| T-00.3 | Task | EP-00 | Local development environment with PostgreSQL and ZITADEL | 3 | MVP | 0 | infra | below |
| T-00.4 | Task | EP-00 | CI pipeline for build, tests and format check | 2 | MVP | 0 | infra | below |
| T-00.5 | Task | EP-00 | Spike: database access Magnum vs. ZIO Quill (ADR 0012) | 3 | MVP | 0 | infra | below |
| T-00.6 | Task | EP-00 | Spike: Flowable under ZIO (ADR 0006) | 3 | MVP | 0 | workflow | below |
| T-00.7 | Task | EP-00 | Spike: deterministic PDF/A rendering (ADR 0008) | 3 | MVP | 1 | rendering | below |
| T-00.8 | Task | EP-00 | Clarification: ZITADEL license (AGPL) and operating model | 1 | MVP | 1 | identity | below |
| T-00.9 | Task | EP-00 | Database foundation: Flyway, tenant context, row-level security | 3 | MVP | 1 | app, kernel | below |
| S-11.a | Story | EP-11 | Login via ZITADEL with tenant resolution | 5 | MVP | 1 | identity, app | below |
| T-00.10 | Task | EP-00 | Frontend scaffold with TypeScript, React and OIDC login | 3 | MVP | 1 | frontend | below |
| S-11.b | Story | EP-11 | Authorization check RBAC+ABAC (basic) | 8 | MVP | 2 | authz | below |
| S-12.a | Story | EP-12 | Audit trail with hash chain and timestamp anchoring | 5 | MVP | 2 | audit | below |
| T-00.11 | Task | EP-00 | Object storage with Object Lock: local solution and adapter | 3 | MVP | 2 | infra, archive | below |
| T-00.12 | Task | EP-00 | Transactional outbox and job queue in PostgreSQL | 3 | MVP | 3 | integration, app | below |
| S-02.a | Story | EP-02 | Manage document classes and field definitions (basic) | 5 | MVP | 3 | documents | below |
| US-02.1 | Story | EP-02 | Status-dependent mandatory fields per document class | 3 | MVP | 3 | documents | FK |
| S-01.a | Story | EP-01 | Create template with placeholders and conditions (CEL) | 8 | MVP | 4 | templates | below |
| S-01.b | Story | EP-01 | Template lifecycle with four-eyes approval | 3 | MVP | 4 | templates | below |
| US-01.1 | Story | EP-01 | Locked and editable sections in templates | 5 | MVP | 4 | templates | FK |
| S-03.a | Story | EP-03 | Create document from an approved template | 5 | MVP | 5 | documents, templates | below |
| S-05.a | Story | EP-05 | Version model with frozen version packages | 5 | MVP | 5 | documents | below |
| US-03.2 | Story | EP-03 | Live preview while filling in | 5 | MVP | 5 | documents, rendering, frontend | FK |
| S-04.a | Story | EP-04 | Approval workflow (basic) with Flowable | 8 | MVP | 6 | workflow | below |
| US-04.1 | Story | EP-04 | Bind approval to a specific version | 3 | MVP | 6 | workflow, documents | FK |
| US-04.2 | Story | EP-04 | Enforce the four-eyes principle technically | 2 | MVP | 6 | workflow, authz | FK |
| US-03.1 | Story | EP-03 | Take over contract party from partner master data | 3 | MVP | 6 | documents, integration | FK |
| US-06.1 | Story | EP-06 | Archivable PDF/A rendition with checksum | 8 | MVP | 7 | rendering | FK |
| US-06.3 | Story | EP-06 | Scheduled publication on the valid-from date | 3 | MVP | 7 | documents | FK |
| US-05.2 | Story | EP-05 | Editorial correction as a minor version | 3 | MVP | 7 | documents, workflow | FK |
| US-06.2 | Story | EP-06 | Acknowledgement of new policies | 5 | MVP | 8 | documents, notifications | FK |
| S-10.a | Story | EP-10 | Central task inbox | 5 | MVP | 8 | notifications, workflow, frontend | below |
| US-10.1 | Story | EP-10 | Delegation for approvers | 3 | MVP | 8 | workflow, authz | FK |
| US-01.2 | Story | EP-01 | Impact analysis before approving a template version | 3 | MVP | 8 | templates | FK |
| S-09.a | Story | EP-09 | Full-text indexing of published documents | 5 | MVP | 9 | search | below |
| US-09.2 | Story | EP-09 | Permission-filtered search results | 5 | MVP | 9 | search, authz | FK |
| US-09.1 | Story | EP-09 | Reference-date search "valid on" | 3 | MVP | 9 | search | FK |
| US-05.1 | Story | EP-05 | Compare two versions | 5 | MVP | 10 | documents, frontend | FK |
| US-07.1 | Story | EP-07 | Review task before due date and recertification | 5 | MVP | 10 | documents, notifications | FK |
| US-08.1 | Story | EP-08 | Set and lift legal hold | 3 | MVP | 10 | archive | FK |
| US-12.1 | Story | EP-12 | Evidence report per document | 5 | MVP | 11 | audit | FK |
| US-14.1 | Story | EP-14 | Stable address for the valid version (intranet) | 3 | MVP | 11 | integration | FK |
| US-15.1 | Story | EP-15 | Compliance cockpit: overdue reviews and open acknowledgements | 5 | MVP | 11 | app, frontend | FK |
| T-00.13 | Task | EP-00 | MVP hardening, load test and acceptance | 3 | MVP | 11 | infra | below |
| US-01.3 | Story | EP-01 | Update clause centrally with list of usages | 3 | R2 | Backlog | templates | FK |
| US-02.2 | Story | EP-02 | Map policies to regulatory requirements | 5 | R2 | Backlog | documents | FK |
| US-03.3 | Story | EP-03 | Bulk generation via interface | 8 | R2 | Backlog | documents, integration | FK |
| US-04.3 | Story | EP-04 | Conditional workflow steps without programming | 5 | R2 | Backlog | workflow | FK |
| US-07.2 | Story | EP-07 | Review task when the parent document changes | 3 | R2 | Backlog | documents, notifications | FK |
| US-08.2 | Story | EP-08 | Review destruction list and approve with dual sign-off | 5 | R2 | Backlog | archive | FK |
| US-10.2 | Story | EP-10 | Reminder before notice period | 3 | R2 | Backlog | notifications | FK |
| US-11.1 | Story | EP-11 | Group template with local extension points | 5 | R2 | Backlog | templates, authz | FK |
| US-11.2 | Story | EP-11 | Semi-annual recertification of permissions | 5 | R2 | Backlog | authz | FK |
| US-12.2 | Story | EP-12 | Find documents relating to a person (data subject access request) | 5 | R2 | Backlog | search, audit | FK |
| US-13.1 | Story | EP-13 | Notice of outdated translation | 5 | R2 | Backlog | documents | FK |
| US-14.2 | Story | EP-14 | Electronic signing with return flow | 8 | R2 | Backlog | integration | FK |

Total MVP: 177 SP · Total R2: 60 SP.

## Descriptions of the technical issues and enablers

### T-00.1 Project scaffold: sbt multi-module, formatting, domain kernel
sbt 1.12 multi-module build according to ADR 0001 (modules with `api`/`core`), central versions in `project/Dependencies.scala`, scalafmt, sbt-tpolecat, identifiers as opaque types, version number, pure lifecycle state machine with tests, health endpoint with OpenAPI.
- `sbt test` passes.
- `GET /health` responds with status and version; `/docs` shows the OpenAPI UI.
- No core module depends on another core module.

### T-00.2 Document architecture decisions as ADRs
ADRs 0001–0014 under `docs/adr/` with comparison of alternatives, consequences, follow-ups and reversibility.
- Each ADR has a status, an alternatives table, positive and negative consequences, reversibility.
- Index in `docs/adr/README.md`.

### T-00.3 Local development environment with PostgreSQL and ZITADEL
`compose.yaml` with PostgreSQL for the platform and for ZITADEL, plus ZITADEL v4.19.5.
- `docker compose up` starts all services without manual steps.
- ZITADEL console reachable, project and application "dms-plattform" created by script.
- Instructions in the README.

### T-00.4 CI pipeline for build, tests and format check
**Clarification needed:** the platform (GitHub Actions, GitLab CI, Bitbucket Pipelines) has not been decided yet.
- The pipeline runs `scalafmtCheckAll` and `test` with `SBT_TPOLECAT_CI=1`.
- Warnings fail the build.
- Dependency cache active.

### T-00.5 Spike: database access Magnum vs. ZIO Quill (ADR 0012)
Prototype of both variants for: tenant context via `SET LOCAL` for row-level security, `FOR UPDATE SKIP LOCKED`, JSONB query, append-only audit insert. Timebox 3 days.
- Comparison by readability, compile time, error handling and testability documented.
- ADR 0012 set to "Accepted".

### T-00.6 Spike: Flowable under ZIO (ADR 0006)
Start Flowable embedded (check version 7.2 vs. 8.0), process with a user task and timer escalation, transaction boundary shared with business data.
- The process runs in an integration test against PostgreSQL.
- Result and version choice added to ADR 0006.

### T-00.7 Spike: deterministic PDF/A rendering (ADR 0008)
Python worker with WeasyPrint renders three reference documents; veraPDF checks PDF/A.
- Ten runs in two containers produce identical checksums (or the cause of the deviation is documented).
- Result in ADR 0008.

### T-00.8 Clarification: ZITADEL license (AGPL) and operating model
- Decision self-hosted vs. hosted service documented.
- AGPL assessment for the planned use is available.

### T-00.9 Database foundation: Flyway, tenant context, row-level security
- Migrations run via a separate start command, not at application startup.
- Tables with `tenant_id` are protected by row-level security; an integration test proves that tenant A sees no rows of tenant B.
- The audit table is protected against UPDATE/DELETE by a trigger.

### S-11.a Login via ZITADEL with tenant resolution
As a user, I want to log in with my company account so that I only see the data of my tenant.
- The backend validates the JWT against the ZITADEL JWKS (signature, expiry, audience).
- The ZITADEL organization is mapped to a tenant of the platform; unknown organizations receive 403.
- A check of `auth_time` (step-up) is available for approvals.

### T-00.10 Frontend scaffold with TypeScript, React and OIDC login
- Login via authorization code flow with PKCE against ZITADEL.
- The API client is generated from the OpenAPI description.
- Basic layout, routing, error display.

### S-11.b Authorization check RBAC+ABAC (basic)
As a business administrator, I want to assign roles per tenant, organizational unit and document class so that access is governed according to section 2.3 of the business concept.
- Deny by default; the decision takes role, tenant, class, confidentiality and status into account.
- Every denial is logged with a reason.
- The permission matrix from section 2.3 of the business concept is covered by test cases.

### S-12.a Audit trail with hash chain and timestamp anchoring
As internal audit, I want every relevant action to be logged in a tamper-proof way.
- Entries contain who, what, on what, when, why, with what (business concept section 7.3).
- Each entry contains the hash of its predecessor; a verification routine detects tampering.
- The daily chain state is anchored with an RFC 3161 timestamp (service configurable).

### T-00.11 Object storage with Object Lock: local solution and adapter
MinIO Community is no longer maintained as ready-made images; choose a local alternative (e.g. Garage, SeaweedFS) and build an S3 adapter.
- Upload, download and Object Lock (retention) verified in an integration test.

### T-00.12 Transactional outbox and job queue in PostgreSQL
- Events are written in the same transaction as the business data.
- Delivery with retries and an idempotency key.
- Jobs are fetched by multiple workers without conflicts using `FOR UPDATE SKIP LOCKED`.

### S-02.a Manage document classes and field definitions (basic)
As a business administrator, I want to create document classes with a metadata profile so that documents are described consistently.
- Classes are hierarchical and inherit profiles.
- Field definitions are central, typed (text, number, amount, date, selection, person, reference) and versioned.
- The core metadata from section 3.2 of the business concept is set for every class.

### S-01.a Create template with placeholders and conditions (CEL)
As a template designer, I want to create templates with typed placeholders, conditions and repeating groups.
- Conditions and calculated fields are evaluated with CEL, with time and size limits.
- Invalid expressions are reported with their position when saving.
- A test mode with sample data shows all condition branches.

### S-01.b Template lifecycle with four-eyes approval
- Status Draft → Test → Approved → Deprecated → Archived.
- A designer cannot approve their own template.
- Only approved templates appear in the catalogue.

### S-03.a Create document from an approved template
As an author, I want to create a document from the template catalogue and fill in fields.
- The document is bound to the template and building-block versions.
- Mandatory fields are checked per status (US-02.1).
- The origin of each field value (manual, system, calculated) is stored.

### S-05.a Version model with frozen version packages
- Approved versions are immutable: content, field values, metadata snapshot, template version, rendition, checksum.
- At most one open draft and at most one valid version per scope.
- Working states in the draft are numbered sequentially.

### S-04.a Approval workflow (basic) with Flowable
As an owner, I want to submit a document so that it is reviewed and approved according to the workflow of its class.
- Sequential and parallel review steps; participants determined from roles.
- Review votes require a justification, except for approval.
- Running cases remain on their workflow version.

### S-10.a Central task inbox
- All tasks (review, approve, acknowledge, periodic review) in one list with due date and context.
- Actions can be executed directly from the list.
- Overdue tasks are highlighted.

### S-09.a Full-text indexing of published documents
- Published versions are indexed via the outbox.
- The index can be fully rebuilt from the database.
- Authorized groups are indexed as well.

### T-00.13 MVP hardening, load test and acceptance
- Load test carried out with the volume figures from section 14.2 of the business concept (reduced).
- Open high-priority defects fixed.
- Acceptance of the MVP stories documented.

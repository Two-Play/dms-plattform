# 0018: Authorization as an in-app policy (RBAC + ABAC)

| Field | Value |
|---|---|
| Status | Accepted (addendum DMS-49) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Access follows the permission matrix of the business concept (2.3): roles (reader, author, owner, reviewer, approver, template designer, records manager, auditor, tenant administrator) grant functions, and attributes restrict them: tenant, organizational unit, document class, confidentiality, status, ownership, assignment to a review step, segregation of duties. Every denial must be logged with a reason (DMS-28). ZITADEL (0005) only provides coarse project roles; document-level rights are the platform's job. Tenant isolation is additionally enforced by row-level security (0014).

## Decision

Authorization is a pure Scala function `Policy.decide(subject, action, resource, now)` in module `authz`, deny by default. Role assignments are stored per tenant in `dms.role_assignment`, scoped to an optional organizational unit and document class, with validity dates. `Authorizer.check` applies the policy for the signed-in caller and logs denials. ZITADEL project roles are used as groups for confidential documents.

## Alternatives considered

| Criterion | Chosen: in-app policy | OpenFGA (relationship-based) | OPA / Cedar (external policy language) |
|---|---|---|---|
| Expressiveness for our rules (status, ownership, SoD) | Full, plain Scala | Relations only; attribute rules need conditions or app logic | Full |
| Testability | Unit tests per matrix cell, compile-time checked | Integration tests against the service | Policy tests in the policy language |
| Operations | None | Separate service and store, data sync | Sidecar or embedded engine, policy distribution |
| List filtering ("all documents I may read") | Must be expressed in SQL separately (search, lists) | Native (`ListObjects`) | Partial evaluation, complex |
| Changing rules without a release | No | Model changes possible | Yes (policy bundles) |
| Effort for one developer | Low | High | Medium |

## Consequences

### Positive

- The whole matrix is visible in one file and covered by one test per cell (99 cases).
- Decisions are fast and deterministic; no extra service or network hop.
- Denials carry a reason and are logged with tenant, user and action.

### Negative / Cost

- Rule changes need a release; tenant-specific rules beyond role scopes are not configurable.
- List and search queries must apply the same rules in SQL (or post-filter), which duplicates logic; search (DMS-60ff.) has to index the relevant attributes.
- Role assignments are managed in the platform, not in ZITADEL; an admin UI is needed.

## Follow-ups

- Admin API and UI to grant and revoke assignments (business concept 2.3, user story US-11.2 for recertification).
- Denials of sensitive actions additionally into the audit trail (0017) once it is merged.
- Re-evaluate OpenFGA if relationship-heavy rules appear (sharing with external reviewers, delegation chains).

## Reversibility

**medium**: Callers only use `Authorizer.check(subject, action, resource)`. Replacing the decision engine changes one module; the assignment table could be synced to an external engine.

## Addendum (DMS-49, 2026-10-28): acting on behalf of a principal

A deputy (ADR 0028) acts within the principal's scope on the principal's workflow tasks. `Policy` stays unchanged; `Authorizer.onBehalfOf(subject, principal, roles)` returns the deputy's subject extended by the principal's assignments of the task's role. The application uses it only for the document of a task the workflow lists the caller as deputy of (`WorkflowTask.deputies`, during an active deputyship): for the vote and for reading that document. Validity, scope and confidentiality of every assignment are still decided by `Policy`.

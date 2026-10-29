# 0018: Authorization as an in-app policy (RBAC + ABAC)

| Field | Value |
|---|---|
| Status | Accepted (addenda DMS-49, DMS-51, DMS-101, DMS-56, DMS-59, DMS-57) |
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

## Addendum (DMS-51/52, 2026-10-28): search filter and allowed groups

The search (ADR 0029) applies the read rules of `Policy` as a filter inside the OpenSearch query (`dms.search.core.AccessFilter`), built from the caller's role assignments at query time; the mapping is documented in ADR 0029 and tested against `Policy` for generated cases. `dms.authz.DocumentResource` builds the `Resource` of a document version for the document API and the search indexer alike. It now fills `allowedGroups` from the optional field `allowedGroups` of the version (before, it was always empty, so confidential versions were visible to author and owner only). New configuration area `Search` (rebuilding the index): `Configure` for tenant administrators.

## Addendum (DMS-101, 2026-10-28): groups from the platform instead of ZITADEL project roles

The sentence "ZITADEL project roles are used as groups for confidential documents" is replaced by ADR 0032: `Subject.groups` are the caller's current memberships in the tenant's access groups (`dms.access_group_member`), and a version names its groups in the core field `allowedGroups`. `Policy.clearedFor` exposes the confidentiality rule on its own (used to filter task inboxes); the decision rules are unchanged. New configuration area `Groups` (administering access groups): `Configure` for tenant administrators.

## Addendum (DMS-56, 2026-10-28): legal holds

Legal holds (ADR 0031) use `ManageRetention`, the action of the matrix row "Retention / legal hold" (records managers only); no separate action. Operations on a hold (create, list, read, add documents, release) check it on the tenant resource (`Resource(tenant)`), so only a records manager assignment for the whole tenant qualifies; each document put under a hold is checked with `ManageRetention` on its `DocumentResource` (latest version), which applies class/unit scope and the confidentiality rules. The document view lists the active holds of a document only to callers with `ManageRetention` on it.

## Addendum (DMS-59, 2026-10-29): compliance officer

New business role `ComplianceOfficer` (business concept 2.2, "Compliance-Beauftragter"; database check extended in V20) and two actions, see ADR 0036: `MonitorCompliance` (a valid version in the compliance cockpit: review deadline and acknowledgement figures; compliance officers in scope) and `ViewAcknowledgementPersons` (who has not acknowledged a valid version; compliance officers in scope and the document's owner, with a stated purpose recorded in the audit trail). Like every business role, the compliance officer reads published versions. `dms.authz.Audience` decides `ReadPublished` for all users of a tenant at once, grouping users with equal assignments and groups; it is tested against `Policy` for generated populations. `DocumentResource.of` builds the resource of a version from its attributes for bulk queries.

## Addendum (DMS-57, 2026-10-29): evidence reports

New action `GenerateEvidenceReport` (ADR 0034): creating and downloading the evidence report of a document, granted to auditors and records managers whose assignment covers the document (class/unit scope) in any status; the confidentiality rules apply unchanged, so a confidential document additionally needs an allowed group. It is checked on the `DocumentResource` of the latest version. The permission matrix of business concept 2.3 has no row for audit reports; the action follows the audit role's "evidence" duties.

# 0036: Compliance cockpit: a compliance officer role, figures computed on request, names only with a stated and audited purpose

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-59) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

US-15.1 (DMS-59): as compliance officer I want to see overdue reviews and open acknowledgements at a glance so I can escalate in a targeted way. The cockpit shows the number and the list of overdue reviews per organizational unit and owner; the acknowledgement rate per document and unit is visible, individual persons only according to purpose limitation ("Zweckbindung"); every key figure leads to its list by a click.

Given:

- Business concept 2.2 names the role "Compliance-Beauftragter" (monitors acknowledgements, review deadlines, deviations; read access to audit reports), distinct from the auditor (temporary, read-only access to a defined scope). The role enum (`dms.authz.Role`, ADR 0018) and the database check of `dms.role_assignment` (V5) know only the nine roles of the permission matrix. Section 12 lists the cockpit for "Compliance, Geschäftsleitung".
- Business concept 10.2: acknowledgement and access records must not be used to monitor performance or behaviour; evaluations are purpose-bound and possibly governed by an agreement with the works council.
- Reviews (ADR 0030): a valid version has `review_due_on`; the daily check moves it to ReviewDue on that date; recertification sets a new date. Acknowledgements (ADR 0027): the audience of a valid version that requires acknowledgement is everyone who may read it (`ReadPublished`), evaluated per request, not stored; ADR 0027 notes that this costs one policy decision per version and reader.
- Organizational unit, confidentiality and allowed groups are core fields of the version (ADR 0019, 0032); the owner is a column of the document.
- Deny by default, also for existence and metadata (business concept 2.3). This change merged first in its sprint and uses migration V20 (DMS-57 uses V21, DMS-58 V22).

## Decision

1. **New business role `ComplianceOfficer`.** Added to `Role` and to the database check of `dms.role_assignment` (migration V20). Its rights (the business concept's matrix has no column for it; PolicySpec documents the column):
   - `ReadPublished` like every business role (so drill-down from the cockpit to a document works; as for every business role in scope, the officer belongs to the acknowledgement audience of ADR 0027).
   - `MonitorCompliance` (new action): see a valid version in the cockpit with its review deadline and its acknowledgement rate. Only compliance officers; decided per version, so an assignment restricted to an organizational unit or class subtree limits the cockpit to that scope, and confidential versions are counted only if the officer is cleared for them (author, owner, allowed group).
   - `ViewAcknowledgementPersons` (new action): see who has not acknowledged a valid version. Compliance officers in scope, and the document's owner (owner role and owner of the document) for their own documents.
   The cockpit endpoints require an active compliance officer assignment (403 otherwise); tenant administrators and auditors do not get the cockpit.
2. **Purpose limitation of names.** The cockpit shows only figures (audience, acknowledged, open, rate) per version and per unit. Names come from one endpoint, `GET /api/v1/compliance/acknowledgements/{documentId}/{versionId}/open?purpose=&reason=`, which needs `ViewAcknowledgementPersons` and a stated purpose (`escalation` or `reminder`) with a reason (1 to 500 characters); without them it answers 403 `purpose_required`. Every successful access is recorded in the audit trail (`compliance.acknowledgement-persons-viewed`, actor, document, version, purpose, number of names, reason in the entry's `reason`) in the same transaction as the read, so names are never shown without a record. The response holds user ids of readers who have not acknowledged, nothing else: no opening or acknowledgement times, no list of who did acknowledge. Owners use the same endpoint and are audited the same way.
3. **Figures computed on request, no summary table.** The cockpit reads, per request and in the caller's transaction: the valid versions with their review date and acknowledgement requirement (`DocumentService.currentlyValid`, one query plus one profile lookup per class version), all active role assignments and current group memberships of the tenant (two queries), and the acknowledgers of valid versions (one query). The audience is computed in memory by `dms.authz.Audience`: users with equal active assignments (role, unit, class) and groups are decided alike by `Policy`, so the policy is evaluated once per distinct profile and version; only author and owner, whom the confidentiality rule names, are decided individually. Acknowledgements count only when the acknowledging user is in the version's audience now. Superseded versions drop out; acknowledgements of a predecessor do not count for its successor.
4. **Overdue** means: the version is valid and its review date has been reached (on or before the tenant's today), or it is ReviewDue. The list shows the review date and the days since. Unit is the version's `organizationalUnit`, owner the document's owner; both may be missing and then form a group of their own (filter value empty).
5. **API.** `GET /api/v1/compliance/overview` (overdue counts per unit and owner, acknowledgement totals and per unit), `GET /api/v1/compliance/overdue-reviews?unit=&owner=`, `GET /api/v1/compliance/acknowledgements?unit=&document=`, and the person list above. The frontend shows the cockpit at `/compliance` (navigation entry for compliance officers): key figure cards and per-unit/per-owner tables link to the filtered lists; names are requested only through an explicit dialog that asks for purpose and reason.

## Alternatives considered

| Criterion (who sees the cockpit) | Chosen: new role ComplianceOfficer | Reuse Auditor | Reuse RecordsManager or TenantAdmin |
|---|---|---|---|
| Fits the business concept (2.2) | Yes: its own role with exactly this task | Auditor is temporary, read-only, scoped to an audit; monitoring deadlines and escalating is not its job | Records management and configuration are other jobs; administrators see no content |
| Purpose limitation | The role is the documented purpose | Auditors would see escalation data for every audit | Mixes duties |
| Effort | Enum value, migration V20, matrix column, dev setup | None | None |
| Matrix tests | One more column | Unchanged | Unchanged |

| Criterion (names of readers) | Chosen: separate endpoint, stated purpose + reason, audited | Names in the cockpit lists for every officer | No names at all |
|---|---|---|---|
| Business concept 10.2 (no behaviour monitoring, purpose-bound) | Names only when needed, each access traceable to a person and reason | Every view exposes names; no trace why | Fully compliant |
| Escalation possible ("gezielt eskalieren") | Yes | Yes | Only per unit, not to persons |
| Works council agreement | Purpose values and the audit entry can be referenced | Hard to justify | – |
| Effort | One endpoint, one audit action, a dialog | Less | Least |

| Criterion (acknowledgement figures) | Chosen: computed on request, profiles × versions | Periodically refreshed summary table (new migration) | Policy per user and version (as the open list of ADR 0027) |
|---|---|---|---|
| Freshness | Exact at request time | Up to one refresh interval old; role changes and new acknowledgements appear late | Exact |
| Consistency with `Policy` | Same decision function, no second implementation | Needs the same computation in a job plus invalidation | Same |
| Cost per request | 4 queries + profiles × versions decisions + one lookup per acknowledgement; a tenant with 10,000 users, 200 distinct profiles and 1,000 policies needs about 200,000 decisions (well below a second) | One query | users × versions: 10,000 × 1,000 = 10 million decisions |
| Memory | All active assignments and acknowledgements of valid versions of the tenant (the 100,000 acknowledgements of the business concept's load model are a few MB) | Small | Same as chosen |
| Effort | One class in authz, two bulk queries | Table with RLS and grants, job, refresh trigger, staleness handling | None |

A SQL-only aggregate (joining role assignments to versions by unit and class path) was also considered: it would duplicate the policy in SQL (scope, administrator exception, confidentiality, author/owner) like the search filter does (ADR 0029), with a second place to keep in sync, and the class path is not stored per version.

## Consequences

### Positive

- Compliance officers see overdue reviews and acknowledgement rates of their scope at once, can drill down into lists, and escalate to owners and units.
- Names of readers are visible only with a reason, to the roles that need them, and every access is in the hash-chained audit trail; the works council and data protection officer can check the use.
- No new table, no job and no staleness: the figures are always consistent with the authorization policy and the acknowledgement records.
- `Audience` is reusable for owners' reporting and reminders (ADR 0027 follow-ups).

### Negative / Cost

- Each cockpit request loads all valid versions, active assignments and acknowledgements of valid versions of the tenant. Very large tenants (hundreds of thousands of users with many distinct profiles) may need a summary table later; the API would stay the same.
- The cost grows with the number of distinct assignment profiles; tenants that give many users individual unit/class combinations benefit less.
- Confidential versions the officer is not cleared for are missing from the figures (deny by default); a tenant has to add its compliance officers to the access groups of confidential policies if they shall be monitored.
- Compliance officers are acknowledgement readers like every business role in scope.
- The reason is passed as a query parameter (the specified GET endpoint); it can appear in access logs and must not contain personal data.
- The cockpit shows user ids, not display names; the platform has no user directory yet.
- Migration V20 merged first; V21 (DMS-57) and V22 (DMS-58) follow in order.

## Follow-ups

- Must be done: notify owners and escalate to the unit's management from the cockpit (notifications, ADR 0030 follow-up).
- Should: owner view of open acknowledgements of their documents in the document page (the API exists); display names from the identity provider; export of the lists (F-15.5).
- Should: further cockpit figures of business concept 12 (documents without owner, uncovered regulatory requirements, deviations from standard clauses).
- Later: summary table if the request time exceeds the budget for large tenants; purpose values configurable per tenant (works council agreement).
- Ruled out: names of readers in the cockpit lists without a stated purpose.

## Reversibility

**high**: The role is an enum value and a check constraint; the cockpit is four read-only endpoints and one frontend route; nothing stores derived data. Replacing the on-request computation by a summary table changes `ComplianceApi` only. Removing the role would need revoking its assignments first.

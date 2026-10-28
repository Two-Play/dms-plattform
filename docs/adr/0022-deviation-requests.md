# 0022: Deviation requests as rows per version, deviation report stored with the version

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-79) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Templates have locked sections whose standard text authors may only change with a deviation request (section, reason, requester, time; business concept 4.1, US-01.1). DMS-36 added the pure rules (`EditRules.check`) and the deviation report (`DeviationReport.compare`) in `templates-api`; DMS-37/38 added document versions as frozen version packages (ADR 0021) but stored no requests, so locked text could not change at all. The workflow (DMS-40) must decide on submission whether the additional review step of the document class is needed (`DeviationReport.requiresExtraReview`), and reviewers must see the report as it was submitted. Requests must be traceable (audit trail), tenant-isolated (ADR 0014) and frozen once the version leaves Draft, also against direct SQL of the application role.

## Decision

Deviation requests are rows in `dms.document_deviation_request`, one per request and version, never deleted: withdrawing sets `withdrawn_by`/`withdrawn_at`; at most one active request per version and section (partial unique index). A guard trigger allows inserts and withdrawals only while the version is a draft. The deviation report is computed by `DocumentService` on every draft change, request, withdrawal and submission and stored as `jsonb` in `dms.document_version.deviation_report` (format of the templates module, `DeviationJson`), frozen by the existing version guard like the content. Submission rejects content with `EditRules` violations, so a submitted version has no unauthorized deviations. Active requests are carried over (with `based_on`) when a new version starts from the valid one. `documents-api` exposes `DocumentVersion.deviations` and `DocumentVersion.requiresExtraReview`; document outbox events carry `requiresExtraReview`.

## Alternatives considered

| Criterion | Chosen: request table + report column | Requests as `jsonb` array on the version | Report computed on demand only (not stored) |
|---|---|---|---|
| Freeze after Draft | Own guard trigger (status of the version) plus the version guard for the report | Version guard covers it (one column more) | Requests frozen by guard; report follows from frozen inputs |
| Withdrawal history, who/when | Row stays with `withdrawn_by`/`withdrawn_at` | Must be modelled inside the array; partial updates of `jsonb` | Same as chosen |
| One active request per section | Partial unique index | Application only | Same as chosen |
| What reviewers saw | Stored report, exactly as submitted | Stored | Recomputed; changes if `DeviationReport.compare` changes (bug fixes, better diffs) |
| Reading the workflow decision | Field of `DocumentVersion`, no template access needed | Same | Needs the template version and the diff in every reader |
| Cost | One table, one column, two triggers | Smaller schema, weaker constraints | No storage, but repeated diffing and template loading |

| Criterion (new versions) | Chosen: carry over active requests | Start without requests |
|---|---|---|
| Author effort | None; the approved deviation stays permitted | Author must request every existing deviation again |
| Extra review of the new version | Required again (report shows the carried deviations) | Required again once re-requested |
| Traceability | `based_on` links to the original request | New, unrelated requests |

## Consequences

### Positive

- Unauthorized deviations never reach review: updates and submission run `EditRules` with the stored requests; the database freezes requests and report from submission on, for every writer.
- The workflow reads one boolean (`requiresExtraReview`) from `documents-api` or from the outbox event, without depending on templates.
- Reviewers see the report as submitted; withdrawn requests remain visible with who and when; every request and withdrawal is in the audit trail in the same transaction.

### Negative / Cost

- The report is redundant with content, template and requests; every draft change recomputes it (an O(n·m) diff of locked sections, small in practice).
- The stored report has a format version of its own (`{"format":1,...}`); a change of the deviation model needs a migration of stored reports like the content format.
- `ContentCodec` (implemented in `app`) grows by the report codec; test stubs must implement it.
- A version that goes back from review to Draft can change its requests again; the next submission recomputes the report.

## Follow-ups

- Must be done: the workflow (DMS-40) adds the extra review step when `DocumentVersion.requiresExtraReview` is true; the frontend shows requests and the report (location, change, reasons).
- To decide: whether carried-over deviations of an already approved version need the extra review again (today: yes).
- Ruled out: requests that approve a change by themselves (approval stays with the extra review step).

## Reversibility

**high**: Rationale: the table and the report column are additive (migration V12). Switching to an on-demand report means dropping the column and computing `DeviationReport.compare` in the read path; the API types stay the same.

# 0077: Evidence reports start after a verified prefix that ends at an anchor, lose their content only after the document, name people in the PDF; the cockpit sends notices, exports through the server and shows two more figures

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-108) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

DMS-108 collects what ADR 0034 and ADR 0036 left open. The owner UI of the name list, display names in the UI and the purpose in the request body are done (ADR 0048, 0060). Open are four points. Each was checked against the code first.

1. **Chain cost.** Every report verifies the whole tenant chain (ADR 0034: "Linear in the chain length per report"). The check found more: nothing verifies the chain on a schedule. ADR 0017 asks for it ("wire ... a scheduled `verify`"), `AuditJobs` schedules only checkpoints and anchors. So a report was the only full verification there was, and a cache has nothing to rest on until a regular run exists.
2. **Retention.** ADR 0034 keeps reports "without retention rule so far". `dms.evidence_report` is insert-only. ADR 0054 destroys versions with four eyes and keeps process records, and a report of a destroyed document can still be created and read (`EvidenceReportApiSpec`). No screen lists earlier reports.
3. **Cockpit.** ADR 0036 names notices, the export (F-15.5) and "further figures". The figures are in chapter 16 of the business concept, not 12 (ADR 0036 and the ticket say 12): documents without owner, uncovered regulatory requirements, deviations from standard clauses. Chapter 16 also says that figures must not allow conclusions about documents the viewer cannot see.
4. **Names in the PDF.** ADR 0037, decision 6 says the opposite: "Legal records keep ids ... a name looked up later might not be the name at the time of the action". The ticket asks for names. The reason of ADR 0037 stands, so the answer must meet it.

Given: the manual acknowledgement reminder (ADR 0048) already reaches compliance officers in scope, with 24 hours per version, but the cockpit does not offer it. Review deadlines (ADR 0057) know the owner, deputies and escalation targets through `ownerDeadlineAudience`. There is no unit management in the data: "Bereichsleitung" of the business concept exists only as whatever `escalateTo` names. `ValidAtApi.Csv` already neutralizes formulas; the owner's CSV in the browser does not (it is built from the list loaded, ADR 0048). The evidence report's PDF is deterministic: "every date comes from the payload" (ADR 0034).

## Decision

### Verified prefix

1. **A regular full verification.** New job `audit.verify`, one per tenant and day, an hour after the anchor (`AuditJobs`). It verifies the whole chain with the newest checkpoint. Every full run, whatever it finds, is a row in `dms.audit_verification_run` (V54, insert-only, RLS, select and insert for `dms_app`). A violation is logged as an error and stays on record.
2. **What is cached.** Not a result but a boundary: a run that found the chain intact records `prefix_seq` and `prefix_hash`, the newest timestamp anchor it reached, with a foreign key to `dms.audit_anchor`. Without an anchor there is no prefix.
3. **When a report may use it.** Only the newest run counts, it must be intact and at most 3 days old. Then, for each report: the anchor still exists and has the recorded hash, its RFC 3161 token is valid, the entry has the recorded hash, every older anchor still has the hash of its entry (one query, no token check), and the document's own entries below the prefix match their hashes and the links of their successors. What follows the prefix is verified entry by entry as before, including later anchors and the checkpoint.
4. **Anything else is a full verification.** If one of these checks fails, or something after the prefix is wrong, the report runs the whole verification and records it. So a report never states less than a full run would, once it sees a reason. A recorded violation closes the shortcut until a later full run finds the chain intact.
5. **The report says so.** `integrity.auditChain.trustedPrefix` (position, hash, time of the full run), `entriesChecked` counts what was read, the PDF has a row "Verified prefix" and a note.

### Retention and list

6. **The rule.** The content of a report is removed when all of this holds: the report is older than `dms.retention.evidenceReportRetentionDays` (default 3650), its document is gone (every version destroyed or discarded), and no legal hold holds the document. A report of a living document stays.
7. **Removal is a tombstone.** `RetentionMaintenance` (ADR 0054) sets the payload to null, with `payload_removed_at` and `payload_removed_by`, and records `document.evidence-report-removed` with report id and checksum. Id, document, creator, time and checksum stay: the audit entry of the creation refers to them. The guard of V54 replaces the immutable trigger: a report only loses its payload, once, never under a legal hold and never while the document exists; rows are never deleted. `GET` of a removed report answers 410 `report_removed`.
8. **List.** `GET /api/v1/documents/{id}/evidence-reports`, the latest first, paged, with the same `authorize` as a new report (ADR 0072), so knowing that a report exists follows the same rule as reading it. The status comes from the payload. The document page lists the reports with download, or with the removal date.

### Names

9. **Snapshot in the report.** New format `dms.evidence-report/2`: `persons` maps user ids to the display name when the report was created, and is covered by the checksum. Ids stay everywhere in the JSON. Reports of format 1 still parse and print ids.
10. **The PDF** shows the recorded name where a person is named, with the id if two people share a name, and the id for someone without a name. A table at the end maps names to ids. It looks up nothing at print time, so the same report always prints the same names. Details inside audit entries stay as stored.

### Cockpit

11. **Notices.** `POST /api/v1/compliance/overdue-reviews/{documentId}/{versionId}/notices` with `kind` (`reminder`, `escalation`) and `reason`. Needs the compliance officer role and `MonitorCompliance` on the version, as the list does; the review must be overdue. A reminder reaches the owner and the owner's deputies, an escalation also the targets of ADR 0057. The sender is never a target: an officer alone in scope reaches the administrators.
12. **Rate limit.** One notice per version and kind within 24 hours, for all officers together (`dms.compliance_notice`, insert-only, checked under an advisory lock, as ADR 0048). 429 `notice_too_soon`; the list shows when each kind is possible again.
13. **What travels.** A fixed text (`review.compliance-reminder`, `review.compliance-escalation`, category `deadline`, ADR 0052) through the outbox. The reason is required and stays in the audit trail (`document.compliance-notice-sent`), like a purpose: it is not sent to anyone. No free text reaches people.
14. **Readers.** The cockpit offers the existing reminder of ADR 0048 on the list of acknowledgements. Nothing changes in its rights or its limit.
15. **Export.** `POST /api/v1/compliance/exports/{list}` for the four lists, CSV as `ValidAtApi.Csv`, column names in `de` or `en`. Same function as the screen, so the same role, scope, confidentiality and filters. The read and the audit entry `compliance.list-exported` (list, filters, rows) share a transaction. `POST` because it writes the audit trail. The list of names has its own export with the same purpose and reason, recorded as `compliance.acknowledgement-persons-viewed` with `format: csv`.
16. **CSV safety.** Every cell from people goes through `Csv.text`: a leading `=`, `+`, `-`, `@`, tab or carriage return, also behind spaces, gets an apostrophe. User ids stay as a column next to the name (ADR 0037). The owner's CSV in the browser gets the same rule.
17. **Figures.** Documents without owner (a valid version, no owner; counted once per document) and deviations from standard clauses (valid versions whose stored report holds deviations from locked sections, and how many), each with its list and over the versions the officer may monitor. **Uncovered regulatory requirements are left out:** the platform has no regulatory mapping (F-02.5), so no number could be true.

## Alternatives considered

| Criterion (speeding up the report) | Chosen: prefix ending at an anchor, regular full run | Cache the result with a time limit | Prefix ending at any verified position | Merkle tree (ADR 0017) |
|---|---|---|---|---|
| Cost per report | Entries since the last anchor before the last run | None | Entries since the last run | Logarithmic |
| A rewritten chain below the prefix | Found at once: the anchor no longer matches | Found at the next run | Only at the next run | Found at once |
| Changed entry of another object, stored hash left | Found by the next full run, at most a day later | Same | Same | Found per proof |
| Depends on the database row being honest | No: the token decides | Yes | Yes | No |
| Works without TSA | No: full verification, as before | Yes | Yes | Yes |
| Effort | One table, one job | Smallest | One table, one job | New hash structure |

| Criterion (what the cache is) | Chosen: insert-only runs, the newest decides | One row per tenant, updated |
|---|---|---|
| A found violation stays on record | Yes | Needs a second place |
| `dms_app` can erase a finding | No | Yes |
| Evidence of the regular check (business concept 7.3) | Yes | Only the last |
| Growth | One small row per tenant and day, more with reports that fall back | None |

| Criterion (when a report's content goes) | Chosen: age and document gone and no hold | Age alone | With the document's destruction | Never |
|---|---|---|---|---|
| Keeps what was stated at a date (concept 16: reproducible reports) | Yes, as long as the document lives | No | Yes, until then | Yes |
| Personal data goes in the end | Yes, late | Yes | Yes, first | No |
| An answer to an audit about a destroyed document | At least the retention period | Yes | No: the proof goes with the decision to destroy | Yes |
| New four-eyes step needed | No: destruction had one, and the period is fixed | Yes, or an unreviewed deletion of a living record's evidence | No | No |

| Criterion (what removal does) | Chosen: payload gone, row stays | Delete the row |
|---|---|---|
| Audit entry still has something to point at | Yes | No |
| Someone asking for the report learns why it is gone | Yes, 410 | No, 404 |
| Personal data left | Creator's id, as in the audit trail | Same in the audit trail |

| Criterion (names in the PDF) | Chosen: names recorded in the report | Looked up when printing | Ids only (ADR 0037) | Names instead of ids in the JSON |
|---|---|---|---|---|
| The name at the time of the report | Yes | No: the name of the day of the download | Not relevant | Yes |
| Same PDF for the same report | Yes | No | Yes | Yes |
| Readable for an auditor | Yes | Yes | No | Yes |
| Stable identifier kept | Yes | Yes | Yes | No |
| Old reports | Stay with ids | Could show names | Stay | Stay |
| Personal data in the payload | More, under the retention above | None added | None added | More |

| Criterion (notice limit) | Chosen: per version and kind, all officers together | Per officer | Unique index per day |
|---|---|---|---|
| A second officer cannot repeat it | Yes | No | Yes |
| Rolling 24 hours | Yes | Yes | No: two notices around midnight |
| Same as manual reminders | Yes | No | No |

| Criterion (export) | Chosen: built on the server | Built in the browser (ADR 0048) |
|---|---|---|
| Audited | Yes, in the transaction of the read | Only by the separate load of the list |
| Same rights as the screen | Yes, same function | Yes |
| Formula safety in one place | Yes | One more place |
| Column names through the i18n catalogs | No: two lists in `ComplianceCsv` | Yes |

The name list of the owner on the document page stays as ADR 0048 decided it: its load is audited with purpose and reason, and its file is that list.

## Consequences

### Positive

- The cost of a report no longer grows with the chain where anchors run; a chain rewritten below the prefix does not get past the anchor.
- The regular integrity check of the business concept (7.3) exists, with its results on record. Before, tampering was only found if someone created a report.
- A report states what it read and what it took over, so a reader can judge it.
- Reports have an end, enforced by the database, and earlier reports can be found and downloaded.
- The PDF is readable and stays reproducible; ids remain the identifier.
- Officers act from the list they look at, with a record of why, and neither a double click nor a colleague repeats a notice.

### Negative / Cost

- **A window remains, by decision of the ticket.** A changed entry of another object below the prefix, with its stored hash left alone, is not seen by a report until the next full run, at most about a day, or up to 3 days if the job fails. Between two runs a report can say `Intact` about such a chain. It says that it relied on a prefix.
- Without TSA trust anchors there are no anchors, so no prefix: reports stay linear, and the daily run adds the same cost once a day.
- The prefix ends at the newest anchor at the last run, so the tail is up to two days of entries, and the anchors of a chain are checked by one query that grows with the days, not with the entries.
- A finding of the job reaches the log only. Nobody is told in the product; reports show it.
- Reports of a living document are never removed, also after decades, with the names in them. Withdrawn drafts cannot be destroyed (ADR 0054), so a document with one keeps its reports.
- Names in reports are personal data the JSON did not hold before.
- The directory may be down: such a report holds fewer names, or none, and says so; it cannot get them later.
- An escalation has no way to reach a unit's management unless `escalateTo` names someone; the fallback is compliance, then administrators.
- A notice is per version: an owner with many overdue documents can get many. There is no digest.
- CSV column names are a second set of texts outside the catalogs; status values stay machine values.
- "Without owner" counts documents with no owner set, not owners who left (business concept 6.2, "verwaist").

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: tell someone when `audit.verify` finds a violation (administrators, compliance), and show the last run in the cockpit.
- Should: orphaned documents as a figure: the owner has no active owner role.
- Should: a removal on request of a data subject, and the retention of reports of living documents, with data protection.
- Could: a digest of notices per owner; a unit management as an escalation target.
- Could: uncovered regulatory requirements, once the regulatory mapping (F-02.5) exists.
- Could: the prefix anchored by write-once checkpoints as well, for installations without TSA.
- Ruled out: trusting a prefix without an anchor; deleting rows of the registry; names looked up when printing.

## Reversibility

**medium**: The prefix is an optimization: a constant of 0 days makes every report verify the whole chain again, and the run table can stay as the record of the regular check. Format 2 stays readable with its field; a format 3 can drop it. Removed payloads cannot be restored, which is their purpose. Notices and exports are additive endpoints and one table.

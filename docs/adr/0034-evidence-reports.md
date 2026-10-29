# 0034: Evidence reports: assembled on request, registered as canonical JSON with its SHA-256, PDF/A printed from the registered JSON

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-57) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

US-12.1 (DMS-57): internal audit generates a complete evidence report for a document to answer audit requests without manual searching. The report contains the version chain, review and approval steps with persons and timestamps, reasons, the acknowledgement rate and the integrity status; the report itself carries its creation time, its creator and a checksum.

Given: versions are frozen packages with database guards (ADR 0021); votes are bound to the SHA-256 of the content under review, approvals record approver, sign-in time and content checksum, deputies are recorded as `acting_for` (ADR 0024, 0028); renditions are PDF/A-2b in object storage with Object Lock, referenced by key, object version and SHA-256 (ADR 0016, 0025); acknowledgements are insert-only, their audience is "everyone who may read the version" under the policy and is not stored (ADR 0027); periodic review tasks (ADR 0030), legal holds (ADR 0031) and deviation requests (ADR 0022) are rows per document or version. Every change is in the per-tenant audit hash chain with RFC 3161 anchors and write-once checkpoints (ADR 0017); the chain can be verified as a whole, not per object. The rendering worker prints any self-contained HTML page as PDF/A (ADR 0025). The authorization policy has no action for audit reports (ADR 0018). The platform has no source of display names for user ids.

## Decision

1. **Assembly on request.** `POST /api/v1/documents/{id}/evidence-report` assembles the report from the business tables in one transaction (document, versions, workflow cases with votes and approvals, deviation requests, acknowledgements, review tasks, legal holds — active and released — and the audit entries whose `object_id` is the document), then, outside that transaction, verifies the tenant's audit chain (links, hashes, anchors with the configured TSA trust anchors, newest write-once checkpoint) and reads every rendition back from object storage against its SHA-256. Further checks: the content SHA-256 of each approved version must still equal the one its approval bound, and its metadata snapshot must name the attached rendition's checksum. Status `Violated` / `Incomplete` / `Intact` with a list of findings.
2. **Acknowledgement rate.** Per published version that requires acknowledgement: acknowledgements by members of the audience divided by the audience, where the audience is evaluated at report time with the policy (`ReadPublished` on the version as if valid) over all users with active role assignments and their access groups, through `dms.authz.Audience` (the same computation as the compliance cockpit, ADR 0036). Acknowledgements by former readers are counted separately. The report states this definition.
3. **Registry and checksum (migration V21).** The report is serialized as canonical JSON (keys sorted, no whitespace, format `dms.evidence-report/1`) that contains report id, creation time and creator. Its SHA-256 over the UTF-8 bytes is the report checksum. `dms.evidence_report` stores the payload text (not jsonb, which would change the bytes) with the checksum; a check constraint enforces `sha256 = SHA-256(payload)`, a trigger makes it insert-only, RLS and select/insert grants as everywhere. The creation is audited as `document.evidence-report-created` with report id and checksum, in the same transaction as the registry row.
4. **Formats.** `GET …/evidence-reports/{reportId}` returns the registered JSON byte for byte (checksum in `ETag` and `Repr-Digest`); `GET …/evidence-reports/{reportId}.pdf` prints it as PDF/A-2b through the rendering worker on request (deterministic: every date comes from the payload, file identifier from the checksum) and validates it with veraPDF. Every PDF page names the report id, creation time, creator and the checksum of the JSON. Verification is described in `modules/audit/README.md`: SHA-256 of the JSON = checksum in the PDF footer = registry = audit entry.
5. **Authorization.** New action `GenerateEvidenceReport` for auditors and records managers on the document (scope by class and organizational unit; confidentiality rules unchanged, so a confidential document needs an allowed group), for creating and for downloading reports.
6. **Persons** are user ids; a note in the report says so.

## Alternatives considered

Where the report lives:

| Criterion | Chosen: registry of canonical JSON + checksum, PDF printed on request | Generate only, nothing stored | Store the PDF in object storage with Object Lock |
|---|---|---|---|
| Verify a report later | Registry, audit entry and re-download of identical bytes | Only the checksum in the audit entry; the content cannot be reproduced (the data and the chain grow) | Stored bytes, WORM-protected |
| Storage | Text row per report (tens of KB) | None | PDF per report plus retention management |
| Reproducing the PDF | Deterministic print from the JSON (needs the worker) | – | Byte-identical download without the worker |
| Effort | Small migration, no storage lifecycle | Smallest | Retention rules, legal hold sync, deletion after retention |

Checksum and format:

| Criterion | Chosen: SHA-256 of canonical JSON, shown in the PDF | SHA-256 of the PDF bytes | Signed report (e.g. PAdES / JWS) |
|---|---|---|---|
| Report "carries" its checksum | PDF footer on every page; JSON via headers, registry, audit | Cannot be inside the bytes it covers | Signature inside the document |
| Independent of renderer versions | Yes: content, not layout, is hashed | No: a new WeasyPrint changes the bytes | Depends on what is signed |
| Proof of origin | Audit chain with TSA anchors and checkpoints | Same | Strong, but needs key management and a signing service |
| Verifier needs | `sha256sum` | `sha256sum` | PDF/JWS tooling, trust in our key |

Integrity scope:

| Criterion | Chosen: verify the whole tenant chain per report | Verify only the document's entries | Use the last scheduled verification result |
|---|---|---|---|
| Detects | Any break, including entries of other objects between the document's entries | Changed entries of the document only, not deleted ones or a rewritten tail | Only what was true at the last run |
| Cost | Linear in the chain length per report | Small | None at report time |

## Consequences

### Positive

- One request answers an audit request completely; the report is reproducible and verifiable with standard tools.
- The database guarantees that a registered checksum matches its payload; the audit entry ties it into the hash chain.
- Tampering with a rendition object or an audit entry is shown in the report (`Violated` with findings), proven by tests.
- PDF/A output reuses the rendering worker and veraPDF; no new infrastructure.

### Negative / Cost

- Creating a report verifies the whole tenant chain and reads every rendition: cost grows with the chain and the number of versions (seconds for large tenants).
- The acknowledgement audience is the current one, not the audience at publication time (not stored, ADR 0027); the report says so.
- Printing the PDF needs the rendering worker at download time; a renderer upgrade may change PDF bytes (the checksum covers the JSON, so verification is unaffected).
- Reports contain personal data (user ids, reasons) and are kept without retention rule so far.

## Follow-ups

- Must be done: retention and deletion rule for evidence reports (US-08.2 / DMS-82), display names once a user directory exists, incremental chain verification (verified prefix cache) if creation becomes slow.
- Possible: list of reports per document in the UI, signed reports (PAdES) if auditors require proof of origin outside the platform, archiving the PDF in object storage.
- Ruled out: hashing the PDF bytes as the report checksum (renderer-dependent).

## Reversibility

**medium**: The registry table and the payload format are versioned (`format`); a new format or storing the PDF additionally can be added without migrating old reports. Dropping the registry would lose the ability to re-download old reports, but their checksums stay in the audit trail.

# 0041: Document attachments: files linked to versions, content-addressed per document, streamed uploads with a scan port, frozen with the version

| Field | Value |
|---|---|
| Status | Proposed (2026-10-29, implemented in DMS-114; addendum DMS-117) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-114: authors add attachments (any file: spreadsheets, drawings, signed scans, price lists) to a document, so that related material is reviewed, approved and archived together with it. Requirements: add, rename and remove while the version is a draft; size limit (configurable, e.g. 50 MB) and a blocklist of executable types; stored in object storage with SHA-256; frozen from approval on like the version (database guard); a new version takes the attachments over without copying bytes; readers and reviewers see and download them with the permissions of the version; a virus-scan hook before an attachment becomes available; attachments with checksums in the evidence report; the same retention and legal hold treatment as renditions. The upload limit must be enforced on the server while streaming, never by buffering whole files in memory.

Given: versions are frozen packages guarded by triggers (ADR 0021); deviation requests are per-version rows carried over by `DocumentService.newVersion` (ADR 0022); renditions live in S3 with Object Lock, referenced by key, object version and SHA-256 (ADR 0016, 0025); the legal hold job sets the object legal hold on every rendition of a held document (ADR 0031); votes and approvals bind the SHA-256 of the canonical content (DMS-41, ADR 0024); the evidence report reads renditions back against their checksums (ADR 0034). The HTTP layer is Tapir on ZIO HTTP; ZIO HTTP aggregated every request body up to 100 KB (`Server.Config.default`), so larger uploads were impossible. ZIO HTTP's own streaming form parser keeps parts without a binary media type in memory and does not end a part's stream when the body fails half-way (the consumer waits forever). Tapir's multipart support sits on that parser and its body limit is a static endpoint attribute. DMS-112 (on-demand draft and copy PDFs, ADR 0039) is not on `main` yet.

## Decision

1. **Model (migration V26).** `dms.document_attachment_file`: one row per stored file of a document (SHA-256, size, media type, object key and version, scan status and scanner, who stored it when); **content-addressed per document** (`unique (document_id, sha256)`); insert-only. `dms.document_version_attachment`: the link of a file to a version with the name it has there (`based_on` names the link of the previous version it was taken over from). Names: no path separators or control characters, at most 255 characters, unique per version ignoring case; a file at most once per version. Both tables have RLS on `dms.current_tenant()` and explicit grants (files: select/insert; links: select/insert/delete and update of `name` only).
2. **Freeze.** A guard trigger on the links allows insert, rename and delete only while the version is `Draft` (it locks the version row `FOR SHARE`, so a concurrent submission waits or is seen) and checks that the file belongs to the version's document. Drafts only, not "open": reviewers review a fixed set; a change after a return to draft starts the next round. `AttachmentService` checks the same before (409).
3. **Take-over.** `DocumentService.newVersion` copies the links of the base version into the new draft in the same transaction (new link ids, same files, `based_on`); no bytes are copied. Removing an attachment from a draft deletes only the link; the file stays for the versions that reference it.
4. **Approval covers attachments.** The content SHA-256 that votes and the approval bind (`WorkflowDocuments.contentSha256`) includes the attachments (name and SHA-256, sorted by name) when a version has any; versions without attachments keep their previous checksum.
5. **Upload.** `POST /api/v1/documents/{id}/attachments`, `multipart/form-data` with one part `file` (Tapir endpoint with a streaming body, documented as multipart). The endpoint authorizes first (`EditDraft`, draft only), then parses the body with a small streaming multipart parser of our own (`MultipartStream`: one chunk plus look-ahead in memory) and writes the file part to a temporary file while computing SHA-256 and counting bytes; above the limit it stops at once (413). The whole body is limited to the limit plus 64 KB. Then: name normalized (last path segment), blocklist by extension (configurable, default: executables and scripts of Windows, macOS, Linux and the JVM) and by content (PE `MZ`, ELF, Mach-O, Java class) → 422; if the document already has a file with this SHA-256 it is reused; otherwise the **scan port** runs, and the file is stored as `attachments/<tenant>/<document>/<file id>` with the SHA-256 verified by the server (`x-amz-checksum-sha256`). Finally, in one transaction, the draft is locked and checked again, the file row (on conflict with a concurrent upload of the same bytes: the existing one) and the link are inserted and `document.attachment-added` is audited. An object stored by a request whose link fails is deleted (best effort). The server aggregates bodies up to 1 MB and streams larger ones (`hybridRequestStreaming`); a route transform refuses streamed bodies on every other endpoint (413), so no JSON endpoint collects large bodies.
6. **Virus scan port.** `AttachmentScanner` (`documents-api`): `scan(file, size)` → `Clean`, `NotScanned` or `Infected(signature)`; an unavailable scanner fails the upload (503, fail closed); an infected file is refused (422 `attachment_infected`) and never stored. The scan runs on the complete temporary file before anything is stored or linked, so an attachment never becomes available unscanned by a configured scanner. Default adapter `AttachmentScanner.None` (`dms.attachments.scanner = none`): files are stored as `NotScanned`. Scan status and scanner name are stored with the file and shown in the list and the evidence report.
7. **Reading.** `GET …/versions/{versionId}/attachments` (list with `editable` and the size limit) and `GET …/attachments/{attachmentId}` (download) require `ReadDocument` on the version the attachment belongs to, exactly like reading the version (workflow participants and their deputies included). The download checks the object's SHA-256 (`HEAD`) against the recorded one before sending (500 `attachment_integrity` otherwise), streams the object (the SDK validates the checksum while reading) and always answers `Content-Disposition: attachment` (ASCII fallback plus RFC 8187 UTF-8 name), `X-Content-Type-Options: nosniff`, `ETag` and `Repr-Digest`. Downloads are **not audited**, like reading a version or downloading its rendition; access logging belongs to operations.
8. **Rename and remove.** `PATCH`/`DELETE …/attachments/{attachmentId}` with `EditDraft` on the draft; the version is checked as if it were a draft, so its author gets 409 (frozen) rather than 403 once it is in review. Audited as `document.attachment-renamed` (before/after) and `document.attachment-removed`.
9. **Legal hold and retention.** `LegalHoldSync` sets and releases the object legal hold on the attachment files of a held document together with its renditions; a trigger enqueues the synchronization when a file is stored for a held document. Content addressing per document (not per tenant) keeps this correct: an object never serves two documents with different holds. Retention (Object Lock) on attachments follows renditions in DMS-93.
10. **Evidence report.** Each version lists its attachments (name, media type, size, SHA-256, object, scan status, who added it when) as an optional field of format 1 (left out without attachments), and every file is read back against its SHA-256 (check `attachment checksum (<name>)`; a mismatch makes the report `Violated`). The PDF prints them per version.

## Alternatives considered

Where attachments belong:

| Criterion | Chosen: links per version to files per document | Attachments per document (not versioned) | Bytes copied per version |
|---|---|---|---|
| Reviewed and approved together with the version | Yes: frozen with the version, part of the bound checksum | No: an approved version could gain or lose attachments | Yes |
| New version | Links copied, no bytes | Nothing to do, but history lost | Every file copied in object storage |
| Storage | Once per document and content | Once | Once per version |
| Destruction per version (DMS-82) | Remove links; a file goes when no version references it | Document-wide only | Simple, but wasteful |

Content addressing scope:

| Criterion | Chosen: per document | Per tenant (global dedup) | None (every upload a new object) |
|---|---|---|---|
| Legal hold / retention per object | Follows exactly one document | An object shared by a held and an unheld document needs reference counting across documents | Simple |
| Storage saving | Same file in versions of one document | Also across documents (templates, standard annexes) | None |
| Destruction | Per document | Needs a cross-document reference check | Simple |

Upload transport:

| Criterion | Chosen: multipart, own streaming parser, temp file | Tapir `multipartBody` with `Part[TapirFile]` | Raw body (`application/octet-stream`) with the name in a header |
|---|---|---|---|
| Size limit from runtime configuration, enforced while streaming | Yes | Endpoint attribute fixed at compile time | Yes |
| Memory | One chunk plus look-ahead | ZIO HTTP parser buffers non-binary parts; a failing body can leave the part stream waiting | One chunk |
| Standard browser form / `FormData` | Yes | Yes | Needs a custom header (name encoding) |
| Code to maintain | ~150 lines parser with tests | None | None |

Scanning:

| Criterion | Chosen: synchronous scan before storing | Store first, scan asynchronously (quarantine state until clean) | No scanning |
|---|---|---|---|
| Attachment never available unscanned | Yes | Yes, with a quarantine status and a job | No |
| Upload latency | Scan time (clamd: well below a second for typical office files) | Fast upload, availability later | None |
| Complexity | Port + adapter | State machine, job, UI for "being scanned" | None |

Download audit: auditing every download (like the draft PDFs of ADR 0039) was considered; rejected for now because reading versions and renditions is not audited either and the hash chain would grow with every read. Can be added with one audit call if required.

## Plugging in ClamAV

Implement `AttachmentScanner` in `app` (or an adapter module) against `clamd`'s TCP protocol: open a connection to `clamd` (default port 3310), send `zINSTREAM\0`, then the temporary file in chunks of at most `StreamMaxLength` (each chunk prefixed with its length as 4-byte big-endian integer), finish with a zero-length chunk, read the reply: `stream: OK` → `Clean`, `stream: <signature> FOUND` → `Infected(signature)`, anything else or a timeout → `ScanError.Unavailable`. Register it in `AttachmentsConfig.attachmentScanner` (e.g. `scanner = clamav` with host, port and timeout settings) and run `clamav/clamav` (with `freshclam`) as a sidecar like the rendering worker. `clamd`'s `StreamMaxLength` must be at least the attachment limit. Test it with the EICAR test file (the test scanner in `AttachmentApiSpec` already uses its signature).

## Consequences

### Positive

- Attachments are part of the version package: frozen by the database for every writer, covered by the approval's checksum, taken over by the next version without copies, listed and verified in evidence reports, protected by the object legal hold.
- Uploads of any size up to the limit never sit in memory; the limit and the blocklist come from configuration; the scan runs before anything is stored.
- The scan port makes ClamAV (or a commercial scanner) a configuration and adapter change.
- Other endpoints keep a body limit (1 MB instead of ZIO HTTP's former 100 KB).

### Negative / Cost

- Uploads are buffered on local disk (temporary file) for the hash and the scan; the instance needs temporary space for concurrent uploads (limit × parallel uploads).
- Our own multipart parser is code to maintain (tested with every chunk split, RFC 8187 names, malformed bodies).
- Without a configured scanner files are stored as `NotScanned`; operations must decide whether that is acceptable.
- Files whose links are removed from every version (and objects of failed requests that could not be deleted) stay in object storage until a clean-up of orphaned objects exists.
- The checksum bound by votes changes when attachments change, which is intended, but every existing approval of a version without attachments is unaffected only because the field is omitted when empty.
- Attachments of a document are content-addressed per document only; identical files in different documents are stored twice.

## Follow-ups

- Must be done (DMS-112 follow-up): appendix "Anlagenverzeichnis" (name, size, SHA-256) in the draft and copy PDFs of ADR 0039 once that code is on `main`.
- Must be done (DMS-93): Object Lock retention on attachment files of approved versions from the retention rule, like renditions.
- Must be done (DMS-82): destruction removes links and deletes files no longer referenced; clean-up job for orphaned unreferenced attachment objects (ADR 0016 follow-up).
- Done (DMS-117, see addendum): ClamAV adapter and sidecar for production; `NotScanned` is refused in the production profile.
- Later: search indexing of attachment text (OCR worker), attachment previews, download audit if auditors require it.
- Ruled out: attachments per document without versioning; copying bytes per version.

## Reversibility

**medium**: The tables are small and self-contained; moving to tenant-wide content addressing is a data migration of keys plus reference counting. The upload path is behind one endpoint; replacing the parser by a library or the transport by a raw body changes only the HTTP layer. Removing the attachments from the bound checksum would invalidate the comparison for versions approved with attachments.

## Addendum (DMS-117, 2026-10-30): ClamAV adapter, `NotScanned` refused in production

Status of this addendum: Proposed.

### Context

The scan port (decision 6) had only the no-op adapter: every file was stored as `NotScanned`. Production needs a real scanner for every path that stores or processes an uploaded file: attachments, originals of file documents (ADR 0042), Word templates and the upload preview of the upload flow (ADR 0049). All of them already call `AttachmentUploads.scan` on the complete temporary file before anything is stored or converted, so the decision is about the adapter, its configuration, the sidecar and whether the no-op adapter may run in production.

### Decision

1. **Adapter `ClamAvScanner`** (module `app`, `dms.app.ClamAvScanner`) as described in "Plugging in ClamAV": one TCP connection to `clamd` per scan, `zINSTREAM\0`, the file in length-prefixed chunks of 64 KiB, a zero-length chunk, the reply up to `\0`. `stream: OK` → `Clean`; `stream: <signature> FOUND` → `Infected(signature)` (422 `attachment_infected`, nothing stored); anything else (`INSTREAM size limit exceeded`, other `ERROR` replies), a refused connection, a broken connection or no answer within the timeout → `ScanError.Unavailable` (503 `scanner_unavailable`, fail closed). The scanner name `clamav` is recorded with every stored file. The application pings `clamd` at start and logs a warning if it does not answer (uploads still fail closed).
2. **Configuration**: `dms.attachments.scanner = none | clamav` (`DMS_ATTACHMENTS_SCANNER`), `dms.attachments.clamav { host, port (3310), timeoutSeconds (60) }` (`DMS_ATTACHMENTS_CLAMAV_HOST`, `…_PORT`, `…_TIMEOUT_SECONDS`).
3. **`NotScanned` in production: refused.** New profile flag `dms.profile = development | production` (`DMS_PROFILE`, default `development`). With `production` and scanner `none` the application does not start, unless `dms.attachments.allowUnscannedInProduction = true` is set explicitly (logged as a warning at every start). In `development` the no-op adapter stays the default, with a warning at start.
4. **Sidecar**: `clamav/clamav:1.5.4`, pinned by digest, as compose service `clamav` with the compose profile `clamav` (not started by `docker compose up -d`; start with `docker compose --profile clamav up -d clamav`), port 9310 on the host, `freshclam` updating the signatures in a named volume. In production `clamd` runs next to the application with outbound access to the signature mirror only, `StreamMaxLength` at least the attachment limit (the image's default 100 MB covers the default limit of 50 MB) and enough memory for the signatures (about 1.5 GB).
5. **Tests**: `ClamAvScannerSpec` against an in-process fake `clamd` (clean, infected, chunking, size limit, timeout, closed connection, unreachable) and against the real `clamd` of the pinned image (Testcontainers, bundled signatures without `freshclam`) with the EICAR test file; the upload paths (attachments, originals, upload preview) with the adapter against the fake.

### Alternatives considered

| Criterion (protocol) | Chosen: `clamd` `INSTREAM` over TCP | `clamdscan` CLI in the application container | Shared volume + `SCAN <path>` |
|---|---|---|---|
| Extra binaries in the application image | None | ClamAV client | None |
| File transfer | Streamed over the socket | Streamed by the CLI | Needs a volume shared with `clamd` |
| Runs as separate container / host | Yes | Yes | Only on the same volume |
| Error handling | Typed replies, timeouts in the JVM | Exit codes and output parsing | Like INSTREAM, plus file permissions |

| Criterion (`NotScanned` in production) | Chosen: refused by profile, explicit opt-out | Always allowed (warning only) | Never allowed (no-op adapter removed) |
|---|---|---|---|
| Fail-safe default for production | Yes, once `DMS_PROFILE=production` is set | No | Yes |
| Local development without ClamAV | Yes | Yes | No: every developer runs `clamd` (1.5 GB) |
| Escape for installations without a scanner (e.g. scanning by the storage gateway) | Explicit, logged | Implicit | None |

| Criterion (sidecar in compose) | Chosen: compose profile `clamav` | Always started | Not in compose |
|---|---|---|---|
| Shared local stack unchanged | Yes | No: 1.5 GB more memory and a signature download for everyone | Yes |
| Local end-to-end test with a real scanner | One command | Yes | Manual container |

### Consequences

- Positive: production uploads are scanned by ClamAV before anything is stored or converted; a scanner outage stops uploads instead of letting unscanned files through; a forgotten scanner configuration stops a production start instead of silently storing `NotScanned` files.
- Positive: the adapter is protocol code without dependency; tested against the real `clamd` in CI.
- Negative: `clamd` needs about 1.5 GB of memory and signature updates (network access to the ClamAV mirrors); its first start takes a while (download of the signatures).
- Negative: scan time adds to the upload latency (well below a second for office files, seconds for large archives); scans of very large files are bounded by `timeoutSeconds`.
- Negative: the profile flag is new; until deployments set `DMS_PROFILE=production`, the production guard is not active (follow-up: other production checks such as the webhook development key use the same flag).
- Negative: the CI pulls the ClamAV image (about 390 MB).

### Follow-ups

- Must be done: deployments set `DMS_PROFILE=production`, `DMS_ATTACHMENTS_SCANNER=clamav` and run `clamd` with `freshclam`; monitor signature age (`clamd` `VERSION`).
- Should be done: use `dms.profile` for the other production checks (webhook development key, TSA trust).
- Later: rescan stored files after signature updates (quarantine state), a health endpoint entry for the scanner.

### Reversibility

**high**: The adapter sits behind the scan port; another scanner is another adapter and a configuration value. The profile flag and the production guard are configuration checks at start.

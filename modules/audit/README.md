# Module `audit`

Audit trail with hash chain and timestamp anchoring (business concept 7.3, ADR [0017](../../docs/adr/0017-audit-hash-chain.md)).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Usage

```scala
db.transact(tenant) {
  for
    _ <- documents.approve(id)                       // business change
    _ <- auditTrail.record(AuditEvent(               // joins the same transaction
           actor = Actor(userId, ActorKind.Person, onBehalfOf = None),
           action = "document.approved",
           target = AuditObject("document", Some(id), Some("2.0")),
           channel = Channel.Ui,
           details = Jsonb("""{"fields":{"status":{"before":"in_review","after":"approved"}}}"""),
           reason = Some("Approved in workflow step 2"),
           correlationId = Some(requestId)))
  yield ()
}
```

`record` must run inside `Db.transact(tenant)`; the entry commits or rolls back with the business change. The tenant comes from the transaction, the timestamp from the clock (truncated to microseconds, PostgreSQL's precision). `verify(tenant)` and `AuditAnchoring.anchor(tenant)` open their own transactions.

## Storage

`dms.audit_event` (migrations V1, V2, V4): append-only (no UPDATE/DELETE grant for `dms_app`, trigger blocks the owner too), row-level security per tenant. Each tenant has its own chain: `chain_seq` runs 1, 2, 3, … without gaps (`unique (tenant_id, chain_seq)`), `prev_hash` is the `hash` of the entry at `chain_seq - 1`, `hash_format` names the serialization below.

**Genesis:** the entry with `chain_seq = 1` has `prev_hash = NULL` (enforced by a check constraint); NULL is serialized as an absent field, not as zero bytes.

**Concurrency:** `record` takes `pg_advisory_xact_lock(hashtextextended('dms.audit_event:' || tenant_id, 0))`, then reads the head in a new statement (fresh snapshot under READ COMMITTED). Appends of one tenant are serialized until commit; other tenants are not blocked (except on a 64-bit hash collision, which only costs throughput). The unique constraint is the safety net.

## Hash format 1

```
hash = SHA-256( 0x01 || F(tenant_id) || F(chain_seq) || F(prev_hash) || F(occurred_at) || F(actor_id)
                || F(actor_kind) || F(on_behalf_of) || F(action) || F(object_type) || F(object_id)
                || F(object_version) || F(details) || F(reason) || F(channel) || F(correlation_id) )

F(NULL)  = 0x00
F(value) = 0x01 || uint32 big-endian byte length of value || value bytes
```

| Field | Bytes |
|---|---|
| `tenant_id`, `object_id`, `correlation_id` | UUID in canonical lowercase text form (36 characters), UTF-8 |
| `chain_seq` | signed 64-bit big-endian integer (8 bytes) |
| `prev_hash` | the 32 raw bytes; NULL for the genesis entry |
| `occurred_at` | UTC, `YYYY-MM-DDTHH:MM:SS.ffffffZ`, always six fraction digits, UTF-8 |
| `details` | PostgreSQL's text output of the `jsonb` value (`details::text`), UTF-8 |
| all other columns | the text value, UTF-8 |

The JSON is hashed in the form PostgreSQL stores and returns it (normalized key order and whitespace), not in the caller's original form: `record` normalizes it with `cast(? as jsonb)::text` before hashing. A verifier must therefore read `details::text` from PostgreSQL.

## Verification procedure

`AuditTrail.verify(tenant)` (and any external verifier) reads `dms.audit_event` of one tenant ordered by `chain_seq` and stops at the first violation:

1. `chain_seq` must be 1 for the first row and previous + 1 afterwards, otherwise `MissingEntry` (deleted row).
2. `prev_hash` must be NULL for the first row and equal the previous row's `hash` afterwards, otherwise `BrokenLink`.
3. `hash_format` must be 1, otherwise `UnsupportedFormat`.
4. The recomputed hash must equal `hash`, otherwise `HashMismatch` (changed content).
5. Every anchor in `dms.audit_anchor` with `anchored_until_seq` = this row must have `chain_head_hash` = `hash`, and its `tsa_token` must be a valid RFC 3161 token (SHA-256 message imprint equal to `chain_head_hash`, signature valid for the embedded signer certificate), otherwise `AnchorMismatch`.
6. An anchor beyond the last row means the anchored tail was deleted: `MissingEntry`.

Steps 1 to 4 detect changes by anyone who cannot recompute hashes. Step 5 also detects an operator who rewrites the whole chain consistently: the timestamped head hash from the TSA no longer matches. Changes after the last anchor are only detectable by steps 1 to 4, so anchor regularly.

Minimal external check of one row (Python, `psycopg` row with the columns above):

```python
import hashlib, struct
from datetime import timezone
def f(v): return b"\x00" if v is None else b"\x01" + struct.pack(">I", len(v)) + v
def t(s): return None if s is None else str(s).encode()
def entry_hash(r):  # r: dict with the column values; details = details::text
    ts = r["occurred_at"].astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    fields = [t(r["tenant_id"]), struct.pack(">q", r["chain_seq"]), r["prev_hash"], t(ts),
              t(r["actor_id"]), t(r["actor_kind"]), t(r["on_behalf_of"]), t(r["action"]),
              t(r["object_type"]), t(r["object_id"]), t(r["object_version"]), t(r["details"]),
              t(r["reason"]), t(r["channel"]), t(r["correlation_id"])]
    return hashlib.sha256(b"\x01" + b"".join(f(x) for x in fields)).digest()
```

A known test vector is pinned in `AuditHashSpec`.

## Timestamp anchoring (RFC 3161)

`AuditAnchoring.anchor(tenant)` reads the chain head, requests an RFC 3161 timestamp over its hash (SHA-256 imprint, nonce, `certReq`), checks the token and stores it in `dms.audit_anchor` (append-only, RLS). It does nothing if the chain is empty or the head is already anchored. The TSA call happens between two short transactions. `AuditAnchoring.daily(tenants)` runs this once a day; the application forks it (not wired yet).

Configuration (`TsaConfig`): `url` (default `https://freetsa.org/tsr`, **for development only**; production needs a qualified or contractually bound TSA), `timeout` and `trustedCertificates` (PEM files with the CA certificates of the TSA, loaded into `TsaTrust`). A token is accepted only if its signer certificate has the critical extended key usage `id-kp-timeStamping` and chains to one of these trust anchors at the token's time; with no trust anchors configured every token is rejected (fail closed). For development with freetsa.org, configure its CA certificate from the freetsa.org website. Revocation (CRL/OCSP) is not checked yet. Tests use an in-process TSA on localhost whose certificate is issued by a test CA (`TestTsa`) and never call the internet; a second TSA from a foreign CA proves rejection.

## Checkpoints in write-once storage (DMS-74)

Timestamp anchors are written once a day, so entries after the last anchor could be cut off the end of the chain without breaking a link. `AuditCheckpoints.write(tenant)` therefore copies the chain head into object storage (module `storage`, ADR 0016) with Object Lock in **compliance** mode: key `audit-checkpoints/<tenant>/<chain seq, 20 digits>`, retention `CheckpointConfig.retention` (default 11 years), content format 1 as `name=value` lines (`format`, `tenant`, `chainSeq`, `chainHeadHash`, `writtenAt`).

`AuditCheckpoints.verify(tenant)` runs the chain verification and then checks every version of the newest checkpoint: the chain must reach its position and the entry there must have the recorded hash. A cut tail or a rewritten chain shows up as `CheckpointMismatch`. A forged later version of a checkpoint object (the bucket is versioned, locked versions cannot be removed) is detected as well.

The unprotected window shrinks to the checkpoint interval.

## Scheduling (DMS-75)

`AuditJobs` turns the periodic work into jobs of the PostgreSQL job queue (ADR 0010): `tick` enqueues one `audit.checkpoint` job per tenant and checkpoint window and one `audit.anchor` job per tenant and day (due at `anchorHourUtc`). Idempotency keys (`audit.checkpoint:<tenant>:<window>`, `audit.anchor:<tenant>:<date>`) make it safe for every instance to tick; the job runner makes sure only one worker runs each job, with retries. The app (`dms.app.Background`) runs the scheduler and a job runner; settings under `dms.audit` (`checkpointIntervalSeconds`, default 300; `anchorHourUtc`, default 2; `tsaUrl`; `tsaTrustedCertificates`). Without trusted TSA certificates anchoring is disabled with a warning and only checkpoints run.

## Evidence reports (DMS-57)

ADR [0034](../../docs/adr/0034-evidence-reports.md). `AuditEvidence` (api) gives the evidence report of a document read access to the chain: `entriesOf(objectId)` returns the stored entries about an object with their hashes (inside `Db.transact`), `integrity(tenant)` runs the full verification including the newest checkpoint (`AuditCheckpoints.verify`) and adds the newest anchor (`anchoredUntilSeq`, TSA time) and the newest checkpoint position. If object storage cannot be read, the verification falls back to links, hashes and anchors and reports `checkpointProblem`. The application builds it with the configured TSA trust anchors (`dms.audit.tsaTrustedCertificates`), so anchors are checked; without trust anchors any stored anchor fails verification (fail closed).

`EvidenceReportRegistry` / `EvidenceReportStore` keep the generated reports in `dms.evidence_report` (migration V21): id, document, payload format, creation time, creator, the payload text and its SHA-256. Insert-only (trigger `dms.reject_modification`, select/insert grants for `dms_app`), RLS per tenant, and a check constraint `sha256 = encode(sha256(convert_to(payload, 'UTF8')), 'hex')`, so no row can carry a checksum that does not match its payload.

### Report format 1 (`dms.evidence-report/1`)

Attachments (DMS-114) were added to format 1 as an optional field, left out for versions without attachments, so reports of documents without attachments keep their exact layout; the content SHA-256 of a version with attachments covers their names and SHA-256 (ADR 0041).

The payload is a JSON object with `format`, `reportId`, `createdAt`, `createdBy`, `tenantId`, `document`, `versions` (each with status, change kind and reason, creator, release, validity period, content SHA-256, rendition, attachments (only for versions that have any, DMS-114: name, media type, size, SHA-256, object key and version, virus scan status, who added it when), deviation requests, workflow cases with every vote — round, step, lane, voter, `actingFor` (deputy), decision, reason, time, sign-in time, content SHA-256, invalidation — and the approval, acknowledgement rate and entries, periodic review tasks), `legalHolds`, `auditTrail` (the document's chain entries with `chainSeq` and stored `hash`), `integrity` and `notes`. Persons are user ids (no display-name source yet). Times are ISO 8601 UTC.

It is serialized canonically: object keys sorted by UTF-16 code units at every level, no whitespace, absent optional fields left out, strings escaped as JSON. The **report checksum** is the lowercase hex SHA-256 of the UTF-8 bytes of exactly this text.

`integrity.status` is `Violated` if the chain verification found a violation, the document's entries are not in the verified part of the chain, a rendition or an attachment file read back from object storage does not match its stored SHA-256 (check `attachment checksum (<name>)`), the stored content of an approved version no longer has the SHA-256 its approval bound (DMS-41), or the metadata snapshot names a different rendition checksum; `Incomplete` if nothing is violated but something could not be checked (object storage unavailable, checkpoints unreadable); otherwise `Intact`. Entries after the newest anchor or checkpoint are listed in `findings` (not yet anchored) without changing the status.

### Verifying a report

1. Download the JSON form: `GET /api/v1/documents/{id}/evidence-reports/{reportId}` returns the registered payload byte for byte (`ETag` and `Repr-Digest` carry its SHA-256).
2. Compute `sha256sum` of the downloaded file. It must equal the checksum printed in the footer of every page of the PDF (`SHA-256 (JSON payload)`), the `sha256` returned on creation, and the `details.sha256` of the audit entry `document.evidence-report-created` of the document (which is itself protected by the hash chain, anchors and checkpoints).
3. The PDF (`GET …/evidence-reports/{reportId}.pdf`, PDF/A-2b) is printed from the registered JSON on request; its file identifier is the first 32 hex digits of the checksum and its keywords contain `sha256:<checksum>`.

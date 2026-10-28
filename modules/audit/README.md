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

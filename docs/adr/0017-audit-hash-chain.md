# 0017: Audit trail as hash chain per tenant with RFC 3161 anchoring

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-29) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The business concept (7.3) requires an append-only, cryptographically chained audit trail that even administrators cannot change or delete unnoticed, a regular integrity check with a report, and a trustworthy time source. Audit entries are written in the same transaction as the business change (0001). Tenants are isolated with row-level security and must be exportable and separable (0014). An operator with database owner rights can bypass triggers and grants, so the protection must also hold against someone who can rewrite rows and recompute hashes.

## Decision

Each tenant has its own hash chain in `dms.audit_event`: an entry stores its gap-free position `chain_seq`, the predecessor's hash and SHA-256 over a versioned, length-prefixed serialization (format 1, documented in `modules/audit/README.md`). Appends are serialized per tenant with a transaction-scoped advisory lock. Once a day the chain head of each tenant is timestamped by an RFC 3161 timestamp authority (configurable URL) and the token stored in `dms.audit_anchor`.

## Alternatives considered

Chain structure:

| Criterion | Chosen: chain per tenant | Global chain | Merkle tree per day |
|---|---|---|---|
| Write contention | Per tenant only | All tenants serialized on one lock | None on write; tree built by a job |
| Tenant isolation (0014) | Verification and export per tenant, under RLS | Verification needs all tenants' rows; export breaks the chain | Per tenant possible |
| Detects deletion | Gap in `chain_seq` or broken link | Same, but across tenants | Only for closed days; open day unprotected |
| Verification cost | Linear per tenant | Linear over everything | Logarithmic proofs per entry |
| Complexity | Low | Low | High (tree storage, proofs, day boundaries) |
| Protection before the job ran | Immediate (on commit) | Immediate | Only after the daily tree is built |

Anchoring:

| Criterion | Chosen: RFC 3161 TSA | No anchoring | Public ledger (blockchain) |
|---|---|---|---|
| Detects a consistently rewritten chain | Yes, up to the last anchor | No | Yes, up to the last anchor |
| Trustworthy time source (7.3 "when") | Yes, TSA time; qualified TSA possible (eIDAS) | Server clock only | Block time, imprecise |
| Legal recognition | Established standard, qualified timestamps | None | Unclear |
| Cost / dependency | Free (development) to paid TSA; HTTP call per tenant per day | None | Transaction fees, wallet and key management |
| Data protection | Only a hash leaves the system | — | Only a hash, but published forever |

Canonical serialization: length-prefixed fields with a format byte were chosen over canonical JSON (RFC 8785) because they need no JSON canonicalizer in every verifier and cannot be ambiguous; the `details` JSON itself is hashed in PostgreSQL's `jsonb` text form.

## Consequences

### Positive

- Business change and audit entry commit together; the chain cannot fork (advisory lock, unique `(tenant_id, chain_seq)` as safety net).
- Manipulated, deleted or relinked entries are found by `AuditTrail.verify`; a fully rewritten chain is found by the anchors.
- Tenants stay independent: verification, export and separation work per tenant.
- External verifiers can recompute hashes from the documented format without our code.

### Negative / Cost

- Appends of one tenant are serialized until commit; long transactions that write audit entries block other writers of the same tenant.
- Changes after the last anchor (up to one day) are only protected against people who cannot recompute hashes.
- Deleting only the newest, not yet anchored entries is not detectable by the chain alone.
- The `details` hash depends on PostgreSQL's `jsonb` text output, which has been stable for years but is not a formal standard; a change would need a new hash format.
- Signer certificates of TSA tokens are checked for signature validity, not yet against a trusted root.
- Dependency on BouncyCastle (`bcpkix`) and on the availability of the TSA (anchoring retries the next day).

## Follow-ups

- Must be done: choose a production TSA (qualified per eIDAS or contractually bound) and configure its trust anchor; verify the TSA certificate chain.
- Must be done: wire `AuditAnchoring.daily` and a scheduled `verify` with a report into the application (7.3 "regular integrity check"), including the list of tenants for system jobs.
- Must be done: audit report export per document, period or person (7.3).
- Must be done: re-timestamp tokens before the TSA certificate or algorithm expires (long-term archiving, RFC 4998 style).
- Ruled out: storing audit entries without a chain or hashing in the database (`pgcrypto`), since the format must be verifiable outside PostgreSQL.

## Reversibility

**medium**: The hash format is versioned (`hash_format`), so a new serialization can be introduced for new entries without rewriting old ones. Changing the chain structure (e.g. to a Merkle tree) or the anchoring method only affects `audit-core` and new tables; existing chains and tokens stay verifiable with the old procedure.

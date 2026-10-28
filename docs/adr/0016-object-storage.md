# 0016: Object storage: S3 API with Object Lock, SeaweedFS for local development and CI

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, Philippe: SeaweedFS) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

ADR 0009 stores binary files (source files, PDF/A renditions, archive packages) in S3-compatible object storage and protects released versions with Object Lock (business concept 7.4: immutable for the retention period). Production may run on any S3 service with Object Lock: a cloud provider's S3 or an on-premises system such as Ceph RGW.

Local development and CI need an S3 server that implements the full Object Lock surface we code against: buckets created with Object Lock (implies versioning), `PutObject` with retention and legal hold, `PutObjectRetention` in compliance and governance mode, `PutObjectLegalHold`, rejection of deletes of a locked version, and the governance bypass header. It must be available as a pinned container image, start fast and run under Testcontainers. MinIO, the usual choice, stopped publishing community binaries and images in October 2025 and is distributed as source only (MinIO README; [Minimus](https://www.minimus.io/post/minio-docker-image-changes-how-to-find-a-secure-minio-alternative), [madewithlove](https://madewithlove.com/blog/thanks-for-all-the-buckets/)).

## Decision

The application codes against the plain S3 API through the AWS SDK for Java v2 (module `storage`, `dms.storage.ObjectStore`); no server-specific API is used. For local development and CI we use **SeaweedFS** (`chrislusf/seaweedfs:4.51`, pinned by digest, `weed mini` single process), in `compose.yaml` and in Testcontainers. Production requires any S3-compatible service that passes the same Object Lock test suite (`ObjectStoreSpec`).

## Evaluation (time-boxed, 2026-10-28)

The two most promising candidates were run in Docker and checked with AWS CLI 2.39.0 (`s3api`) against the same script: bucket with Object Lock, versioning status, `PutObject` with `COMPLIANCE` retention and SHA-256 checksum, head with lock state, delete of the locked version with and without governance bypass, shortening and extending compliance retention, overwrite of the key, `GOVERNANCE` delete with and without bypass, legal hold on/off and delete.

| Check | SeaweedFS 4.51 | RustFS 1.0.4 |
|---|---|---|
| Bucket with Object Lock, versioning enabled automatically | ✔ | ✔ |
| `PutObject` with compliance retention; `x-amz-checksum-sha256` verified and returned | ✔ | ✔ |
| `HeadObject` returns mode, retain-until date, SHA-256 | ✔ (also legal hold status) | ✔ |
| Delete of a compliance-locked version rejected (also with bypass header) | ✔ `AccessDenied` | ✔ `AccessDenied` with explanatory message |
| Shorten compliance retention rejected, extend accepted | ✔ | ✔ |
| Overwrite of the key creates a new version, locked version stays | ✔ | ✔ |
| Governance: delete rejected without, accepted with bypass header | ✔ | ✔ |
| Legal hold blocks delete until released | ✔ | ✔ |
| Time to first S3 response (incl. ~0.5 s probe overhead) | ~5.4 s | ~0.8 s |
| Memory after start / image size | ~106 MiB / 735 MB | ~115 MiB / 419 MB |

Both passed every check. The Scala test suite (`ObjectStoreSpec`, 10 tests) then ran against SeaweedFS with the AWS SDK v2 and passed without any ignored test. One SDK interaction was found: sending both `x-amz-sdk-checksum-algorithm` and a precomputed `x-amz-checksum-sha256` makes SeaweedFS reject the request with `IncompleteBody`; the adapter therefore sends either a precomputed header or a trailing checksum, never both.

## Alternatives considered

| Criterion | Chosen: SeaweedFS | RustFS | Garage | Ceph RGW (demo container) | Zenko CloudServer | LocalStack S3 | MinIO (last community image) |
|---|---|---|---|---|---|---|---|
| Object Lock: compliance, governance, legal hold | Yes, tested | Yes, tested | No; versioning and lock endpoints missing ([issue #1127](https://git.deuxfleurs.fr/Deuxfleurs/garage/issues/1127), [PR #1470](https://git.deuxfleurs.fr/Deuxfleurs/garage/pulls/1470) declined) | Yes (reference implementation for on-prem) | Documented ([Zenko API](https://zenko.readthedocs.io/en/2.8.9/reference/apis/cloudserver/object_operations/put_object_legal_hold.html)), not tested | Emulated, not tested | Yes |
| License | Apache 2.0 (separate enterprise edition) | Apache 2.0 | AGPL 3.0 | LGPL 2.1/3 | Apache 2.0 | Auth token required since 2026.03 ([LocalStack](https://blog.localstack.cloud/localstack-for-aws-release-2026-03-0/)) | AGPL 3.0 |
| Images and maintenance | Official image, releases every 1–3 weeks ([releases](https://github.com/seaweedfs/seaweedfs/releases)); project since 2014 | Official image; 1.0 since Sept 2026, frequent releases ([releases](https://github.com/rustfs/rustfs/releases)) | Official image | `quay.io/ceph/demo` not intended for production, community replacements only ([ceph-container](https://www.github.com/ceph/ceph-container)) | Image exists, low release cadence | Account/token needed in CI | No new images since Oct 2025; pinning means no security fixes |
| Maturity of the Object Lock implementation | Dedicated retention test suite, lock fixes in 4.45–4.50 ([DeepWiki](https://deepwiki.com/seaweedfs/seaweedfs/7.1-object-locking-and-worm)) | Young; [CVE-2026-73288](https://app.opencve.io/cve/CVE-2026-73288) allowed deleting compliance-locked objects before 1.0.0-rc.1 | – | Mature | Mature (Scality products) | Emulation | Mature |
| Startup / footprint | ~5 s, ~100 MiB RAM, 735 MB image | <1 s, ~115 MiB RAM, 419 MB image | Fast, small | Heavy: MON, MGR, OSD and RGW daemons in one container | Medium (Node.js) | Medium, large image | Fast, small |
| Testcontainers | `GenericContainer`, wait for log line, credentials via env | `GenericContainer`, credentials via env | – | Awkward | Possible | Dedicated module, token needed | Dedicated module |
| Also a production option on-prem | Yes (`weed mini` and clusters) | Yes | No (no lock) | Yes | Yes | No | No |

SeaweedFS and RustFS are functionally equivalent for our purposes. SeaweedFS is chosen for its longer track record and the number of fixes already made to its Object Lock implementation; RustFS starts faster and has a smaller image, but its Object Lock enforcement had a compliance bypass until recently and the 1.0 line is only weeks old. Because we code only against the S3 API, the server can be swapped by changing the image in two places.

## What production needs

- An S3-compatible endpoint with Object Lock: bucket created with Object Lock enabled, `x-amz-checksum-sha256` support (verification and echo in the response), path-style or virtual-host access (configurable later if needed).
- Bucket provisioning by operations (infrastructure code), not by the application: `ObjectStore.bucketSetup` is for local development and tests only.
- Credentials with put, get, head, legal hold and retention permissions; governance bypass (`s3:BypassGovernanceRetention`) only for an administrative role, if at all.
- Before go-live, run `ObjectStoreSpec` against the target service (it only needs an endpoint and credentials) to confirm Object Lock semantics.

## Consequences

### Positive

- One adapter for local, CI and production; behaviour is verified by an integration test suite against a real server.
- Apache 2.0 server without account, token or licence fee; pinned by digest for reproducible CI.
- SHA-256 is verified end to end by S3 additional checksums without our own metadata conventions.
- The same server is a candidate for small on-premises installations.

### Negative / Cost

- The SeaweedFS image is large (735 MB) and starts in about five seconds, which slows the first CI run on a cold cache.
- S3 reports lock violations as plain `403 AccessDenied`; the adapter needs an extra `HeadObject` to report `StorageError.Locked`.
- The synchronous SDK client blocks a thread per request (blocking pool); acceptable at the expected volume.
- Uploads are single `PutObject` requests (max. 5 GB); stream uploads cannot be retried by the SDK after partial transmission.
- Locally, the compliance mode in a developer's volume cannot be undone either; developers reset by deleting the Docker volume.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Must be done: retention periods per document type before Object Lock is used for business data (ADR 0009).
- Must be done: run `ObjectStoreSpec` against the production target before go-live.
- Later: multipart upload for large archive packages; async client if throughput requires it; job to clean up orphaned unlocked objects (ADR 0009).
- Ruled out: server-specific APIs (SeaweedFS filer, RustFS admin API) in application code; Garage until it supports Object Lock; LocalStack because of the auth token requirement.

## Reversibility

**high**: The application uses only the S3 API. Replacing SeaweedFS locally or in CI means changing the image reference in `compose.yaml` and `ObjectStoreSpec` and rerunning the tests (RustFS already passed the same checks). In production, locked objects stay bound to their storage until the retention ends (ADR 0009), but that concerns the production service, not this decision.

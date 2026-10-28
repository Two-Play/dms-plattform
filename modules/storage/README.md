# Module `storage`

Shared object storage infrastructure (ADR 0009, 0016): an `ObjectStore` on the plain S3 API (AWS SDK for Java v2, synchronous client with the JDK URL connection HTTP client, path-style addressing, static credentials). It works with any S3-compatible server that supports Object Lock; locally and in CI that is SeaweedFS (`compose.yaml`, service `objectstore` on port 9100).

## API

| Operation | Behaviour |
|---|---|
| `put(key, content, contentType, retention, legalHold, expectedSha256)` | Uploads a new version (`ObjectContent.Bytes` or `ObjectContent.Stream` with known length). Returns `PutResult(ObjectVersion(key, versionId), sha256, size)`. Optional `Retention(Compliance \| Governance, until)` and legal hold are set atomically with the upload. |
| `get(key, versionId)` | `ZStream` of the version's bytes; the SDK validates the stored SHA-256 while reading. |
| `head(key, versionId)` | `ObjectInfo` with size, content type, SHA-256, retention and legal hold. |
| `setLegalHold(key, versionId, on)` | Sets or releases a legal hold. |
| `extendRetention(key, versionId, retention)` | Extends a retention. Shortening it, or turning compliance into governance, fails with `InvalidRequest` before any request is sent. |
| `delete(key, versionId, bypassGovernance)` | Permanently deletes a version. Fails with `StorageError.Locked` while a retention or legal hold protects it. |

Errors are typed as `StorageError` (`NotFound`, `Locked`, `ChecksumMismatch`, `InvalidRequest`, `AccessDenied`, `Unavailable`, `Unexpected`). S3 reports Object Lock violations as a plain `403 AccessDenied`; `delete` reads the lock state of the version to report `Locked` instead.

## Checksums

Every upload carries an S3 additional checksum (`x-amz-checksum-sha256`), which the server verifies and returns; the store then compares it with its own SHA-256 and fails with `ChecksumMismatch` if they differ or the server did not confirm one. Nothing is stored as user metadata.

- `Bytes`: the SHA-256 is computed first and sent as a header. A wrong `expectedSha256` fails before the upload.
- `Stream` with `expectedSha256`: the expected value is sent as a header; the server rejects mismatching content.
- `Stream` without `expectedSha256`: the SDK sends the SHA-256 as a trailing checksum (aws-chunked); the store computes it in parallel while the SDK reads the stream.

A stream is read only once. If the SDK has to retry after part of the body was sent, the upload fails instead of resending incomplete data; the caller retries with a fresh stream. Uploads use a single `PutObject` (S3 limit 5 GB); multipart upload is not implemented yet.

## Wiring

```scala
val layer = ZLayer.succeed(config) >>> (ObjectStore.client ++ ZLayer.service[StorageConfig]) >>> ObjectStore.live
```

`ObjectStore.client` closes the S3 client when the scope ends. `ObjectStore.bucketSetup` creates the configured bucket with Object Lock if it is missing (local development and tests only; production buckets are provisioned by operations). Settings live in `application.conf` under `dms.storage` (`DMS_STORAGE_*` environment variables).

## Tests

`ObjectStoreSpec` starts the pinned SeaweedFS image with Testcontainers and covers the roundtrip with checksum, versioning, compliance and governance retention, legal hold and the idempotent bucket setup. Docker is required (see the root README for the socket variables).

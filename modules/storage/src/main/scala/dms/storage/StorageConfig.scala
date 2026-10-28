package dms.storage

/** Connection settings for an S3-compatible object store (ADR 0016).
  *
  * @param endpoint
  *   base URL of the S3 API, e.g. `http://localhost:9100`. Requests use path-style addressing.
  * @param region
  *   signing region; local servers accept any value, `us-east-1` is the conventional default.
  * @param bucket
  *   bucket for all objects of this store. It must have Object Lock enabled (implies versioning).
  */
final case class StorageConfig(
    endpoint: String,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: String
)

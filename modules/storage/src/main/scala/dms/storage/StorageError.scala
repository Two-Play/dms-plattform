package dms.storage

import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception

/** Typed object storage errors. */
enum StorageError:
  /** The key or the requested version does not exist. */
  case NotFound(key: String, versionId: Option[String])

  /** The version is protected by Object Lock (active retention or legal hold) and cannot be deleted. */
  case Locked(key: String, versionId: String, retention: Option[Retention], legalHold: Boolean)

  /** The content does not match the expected SHA-256, or the server did not confirm the checksum. */
  case ChecksumMismatch(key: String, expected: Sha256, actual: Option[Sha256])

  /** The request was rejected because of its arguments, e.g. a retention that would shorten the current one.
    */
  case InvalidRequest(message: String)

  /** The server denied access for a reason other than Object Lock (credentials, policy). */
  case AccessDenied(message: String)

  /** The server could not be reached or the connection failed. */
  case Unavailable(cause: Throwable)

  case Unexpected(cause: Throwable)

object StorageError:

  /** Maps SDK exceptions. `Locked` is never derived here because S3 reports it as a plain 403; the store
    * resolves it by reading the lock state of the version.
    */
  def from(key: String, versionId: Option[String])(t: Throwable): StorageError = t match
    case e: S3Exception =>
      val code = Option(e.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("")
      val text = Option(e.awsErrorDetails()).flatMap(d => Option(d.errorMessage())).getOrElse(e.getMessage)
      (e.statusCode(), code) match
        case (404, _) | (_, "NoSuchKey" | "NoSuchVersion") => NotFound(key, versionId)
        case (403, _)                                      => AccessDenied(s"$code: $text")
        case (400, _)                                      => InvalidRequest(s"$code: $text")
        case _                                             => Unexpected(e)
    case e: SdkClientException => Unavailable(e)
    case e                     => Unexpected(e)

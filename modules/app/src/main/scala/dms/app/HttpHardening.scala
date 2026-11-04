package dms.app

import dms.db.DbError
import sttp.model.StatusCode
import zio.http.{Handler, Path, Request, Response}

import java.util.UUID

/** Protection of the HTTP interface (DMS-60, ADR 0056): security headers on every response and failures that
  * tell clients nothing about the inside.
  */
object HttpHardening:

  /** Added to responses that do not set them themselves. The API answers with JSON and files that are never
    * to be framed, sniffed or kept by shared caches; `/current.html` brings its own, stricter policy.
    */
  val SecurityHeaders: List[(String, String)] = List(
    "X-Content-Type-Options"       -> "nosniff",
    "X-Frame-Options"              -> "DENY",
    "Referrer-Policy"              -> "no-referrer",
    "Cross-Origin-Resource-Policy" -> "same-origin",
    "Content-Security-Policy"      -> "default-src 'none'; frame-ancestors 'none'",
    "Cache-Control"                -> "no-store"
  )

  /** The Swagger UI loads its own scripts and styles. */
  private val DocsHeaders: List[(String, String)] =
    SecurityHeaders.filterNot((name, _) => name == "Content-Security-Policy" || name == "Cache-Control")

  private[app] def withSecurityHeaders(path: Path, response: Response): Response =
    val headers = if path.encode.startsWith("/docs") then DocsHeaders else SecurityHeaders
    headers.foldLeft(response) { case (r, (name, value)) =>
      if r.headers.get(name).isDefined then r else r.addHeader(name, value)
    }

  def securityHeaders[R](
      handler: Handler[R, Response, Request, Response]
  ): Handler[R, Response, Request, Response] =
    Handler.fromFunctionHandler[Request](request =>
      handler.map(withSecurityHeaders(request.path, _)).mapError(withSecurityHeaders(request.path, _))
    )

  /** A database failure as a response: an overloaded database as 503, which clients may retry, everything
    * else as 500. Driver messages name tables and constraints and may quote values, so they are only logged,
    * under a reference the client can quote.
    */
  def databaseError(error: DbError): (StatusCode, ApiError) =
    val reference = UUID.randomUUID().toString.take(8)
    logger.error(s"Database failure [$reference]: $error")
    error match
      case DbError.Unavailable(_) =>
        (
          StatusCode.ServiceUnavailable,
          ApiError("unavailable", s"The service is busy, please try again (reference $reference).")
        )
      case _ =>
        (
          StatusCode.InternalServerError,
          ApiError("database_error", s"The request could not be completed (reference $reference).")
        )

  private val logger = org.slf4j.LoggerFactory.getLogger("dms.app.HttpHardening")

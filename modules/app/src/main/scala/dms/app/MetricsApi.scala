package dms.app

import dms.db.Db
import dms.db.queue.{JobQueue, QueueDepth}
import dms.rendering.RenditionRequest
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.ztapir.*
import zio.*

/** Business figures for monitoring (business concept chapter 13, DMS-137, ADR 0079), in the text format of
  * Prometheus, which other collectors read as well.
  *
  * The figures are read from the database when asked: the queue is shared by all instances, so every instance
  * answers with the same numbers and none has to keep a state. Like `/ready` this needs no sign-in, and it
  * says nothing about tenants, documents or users.
  */
object MetricsApi:

  private val ContentType = "text/plain; version=0.0.4; charset=utf-8"

  val metrics: ZServerEndpoint[Db, Any] =
    endpoint.get
      .in("metrics")
      .out(header(HeaderNames.ContentType, ContentType))
      .out(stringBody)
      .errorOut(statusCode(StatusCode.ServiceUnavailable).and(stringBody))
      .description(
        "Business figures in the text format of Prometheus: the depth of the rendering queue by state, and how " +
          "long the oldest due job has been waiting. 503 without the database."
      )
      .tag("Operations")
      .zServerLogic(_ =>
        ZIO
          .serviceWithZIO[Db](JobQueue.depth(_, RenditionRequest.JobKind))
          .map(render)
          .tapError(e => ZIO.logWarning(s"The metrics could not be read: ${e.getClass.getSimpleName}"))
          .orElseFail("the metrics could not be read\n")
      )

  private[app] def render(queue: List[QueueDepth]): String =
    val depth  = queue.map(q => s"""dms_rendering_queue_depth{state="${q.state}"} ${q.jobs}""")
    val oldest = queue.find(_.state == "due").fold(0.0)(_.oldestSeconds)
    (List(
      "# HELP dms_rendering_queue_depth Rendition jobs of all tenants: due (waiting for a worker), delayed " +
        "(waiting for a retry), running.",
      "# TYPE dms_rendering_queue_depth gauge"
    ) ++ depth ++ List(
      "# HELP dms_rendering_queue_oldest_seconds How long the oldest due rendition job has been waiting.",
      "# TYPE dms_rendering_queue_oldest_seconds gauge",
      "dms_rendering_queue_oldest_seconds " + "%.3f".formatLocal(java.util.Locale.ROOT, oldest)
    )).mkString("", "\n", "\n")

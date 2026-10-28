package dms.app

import dms.db.queue.OutboxEvent
import dms.documents.metadata.CoreFields
import dms.documents.{DocumentStatus, Versioning}
import dms.documents.core.DocumentService
import dms.integration.webhooks.{WebhookEventType, WebhookEvents, WebhookNotification}
import dms.kernel.DocumentId
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/** Turns status changes of document versions into webhook events (US-14.1, DMS-58, ADR 0035):
  *
  *   - `document.published`: a version became valid (from Approved; a recertification from ReviewDue is none)
  *   - `document.superseded`: a valid version was replaced by its successor
  *   - `document.withdrawn`: a valid version was withdrawn (withdrawn drafts are none)
  *
  * The data names the version and the stable addresses of the current version. Confidential and strictly
  * confidential documents are not announced: a subscription has no access groups (ADR 0032).
  */
final class DocumentWebhookEvents(documents: DocumentService) extends WebhookEvents:

  def concerns(event: OutboxEvent): Boolean =
    event.aggregateType == "document" && event.eventType == "document.status-changed"

  def describe(event: OutboxEvent): IO[String, Option[WebhookNotification]] =
    ZIO.fromEither(event.payload.value.fromJson[Json.Obj]).flatMap { payload =>
      def text(key: String)   = payload.get(key).collect { case Json.Str(s) => s }
      def status(key: String) = text(key).flatMap(s => Try(DocumentStatus.valueOf(s)).toOption)
      val versionId           = text("versionId").flatMap(s => Try(UUID.fromString(s)).toOption)
      val previous            = status("previousStatus")
      (status("status"), versionId) match
        case (Some(target), Some(version)) => notification(event, target, previous, version)
        case _                             => ZIO.none
    }

  private def notification(
      event: OutboxEvent,
      target: DocumentStatus,
      previous: Option[DocumentStatus],
      versionId: UUID
  ): IO[String, Option[WebhookNotification]] =
    documents.get(DocumentId(event.aggregateId)).mapError(_.describe).map { details =>
      details.versions.find(_.id == versionId).flatMap { v =>
        val eventType = target match
          case DocumentStatus.Published if !previous.contains(DocumentStatus.ReviewDue) =>
            Some(WebhookEventType.Published)
          case DocumentStatus.Superseded => Some(WebhookEventType.Superseded)
          case DocumentStatus.Withdrawn if previous.fold(v.publishedAt.isDefined)(Versioning.isValid) =>
            Some(WebhookEventType.Withdrawn)
          case _ => None
        val values = v.metadataSnapshot.fold(v.metadata)(_.values)
        eventType.filterNot(_ => CoreFields.isRestricted(values)).map { t =>
          val id        = details.document.id.value
          val query     = Option(v.scope).filter(_.nonEmpty).fold("")(s => s"?scope=${encode(s)}")
          val stable    = s"/api/v1/documents/$id"
          val successor = v.supersededBy.flatMap(s => details.versions.find(_.id == s))
          WebhookNotification(
            t,
            id,
            details.profile.classPath.map(_.value),
            v.updatedAt,
            Json.Obj(
              (List(
                "documentId" -> Json.Str(id.toString),
                "versionId"  -> Json.Str(v.id.toString),
                "version"    -> Json.Str(v.label),
                "title"      -> Json.Str(DocumentRenditions.title(details, v)),
                "classKey"   -> Json.Str(details.profile.classPath.lastOption.fold("")(_.value)),
                "classPath"  -> Json.Arr(details.profile.classPath.map(c => Json.Str(c.value))*),
                "scope"      -> Json.Str(v.scope),
                "links"      -> Json.Obj(
                  "current"          -> Json.Str(s"$stable/current$query"),
                  "currentPdf"       -> Json.Str(s"$stable/current.pdf$query"),
                  "currentHtml"      -> Json.Str(s"$stable/current.html$query"),
                  "versionRendition" -> Json.Str(s"$stable/versions/${v.id}/rendition")
                )
              ) ++ v.validFrom.map(d => "validFrom" -> Json.Str(d.toString)) ++
                v.rendition.map(r => "renditionSha256" -> Json.Str(r.sha256)) ++
                successor.map(s =>
                  "supersededBy" -> Json
                    .Obj("versionId" -> Json.Str(s.id.toString), "version" -> Json.Str(s.label))
                ))*
            )
          )
        }
      }
    }

  private def encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

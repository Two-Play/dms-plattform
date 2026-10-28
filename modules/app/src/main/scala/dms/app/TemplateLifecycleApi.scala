package dms.app

import dms.authz.{Action, Resource}
import dms.kernel.TemplateId
import dms.templates.TemplateStatus
import dms.templates.core.TemplateService
import sttp.model.StatusCode
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*

import java.util.UUID
import scala.util.Try

/** Lifecycle of template versions (business concept 4.1): Draft → Testing → Released → Deprecated → Archived,
  * Testing → Draft. Releasing needs a second template designer (four-eyes principle); deprecating is
  * explicit. Only released versions appear in the catalog.
  */
object TemplateLifecycleApi:
  import TemplateApi.{Env, authorized, designer, view}

  private val templateId = path[UUID]("id").description("Template id")
  private val versionNo  = path[Int]("version").description("Template version number")

  val changeStatus: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in("api" / "v1" / "admin" / "templates" / templateId / "versions" / versionNo / "status")
      .in(jsonBody[ChangeTemplateStatus])
      .out(jsonBody[TemplateVersionView])
      .description(
        "Changes the status of a version. Releasing requires a template designer who neither created nor " +
          "changed the version (403 otherwise); other changes require EditTemplates. Releasing a version does " +
          "not deprecate older released versions."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, v, req) =>
          ZIO
            .fromTry(Try(TemplateStatus.valueOf(req.status)))
            .orElseFail(
              (
                StatusCode.UnprocessableEntity,
                ApiError(
                  "invalid",
                  s"unknown status '${req.status}'; use ${TemplateStatus.values.mkString(", ")}"
                )
              )
            )
            .flatMap { to =>
              val change = (s: TemplateService) => s.changeStatus(TemplateId(id), v, to, p.userId)
              if to == TemplateStatus.Released then
                authorized(
                  p,
                  Action.ReleaseTemplate,
                  _.version(TemplateId(id), v).map(version =>
                    Resource(
                      p.tenantId,
                      authorId = Some(version.createdBy),
                      contributors = version.editedBy.toSet
                    )
                  )
                )(change)
              else designer(p)(change)
            }
            .map(view)
      )

  val catalog: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "templates")
      .out(jsonBody[List[CatalogEntry]])
      .description("Released template versions, for creating documents. Open to every business role.")
      .tag("Templates")
      .serverLogic(p =>
        _ =>
          authorized(p, Action.ReadTemplateCatalog, _ => ZIO.succeed(Resource(p.tenantId)))(_.catalog).map(
            _.map(v =>
              CatalogEntry(
                v.templateId.value,
                v.version,
                v.name,
                v.description,
                v.documentClassId,
                v.documentClassVersion,
                v.releasedAt
              )
            )
          )
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(changeStatus, catalog)

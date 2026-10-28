package dms.app

import dms.archive.*
import dms.archive.core.LegalHoldService
import dms.authz.{AccessDenied, Action, Authorizer, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.DocumentStatus
import dms.documents.core.{DocumentError, DocumentService}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import dms.search.{SearchError, SearchQuery}
import dms.search.core.SearchIndex
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** A search whose result is put under legal hold: the parameters of `GET /api/v1/search`. */
final case class HoldSearchFilter(
    /** Query text as in the search. */
    q: Option[String],
    /** Class keys; a class includes its subclasses. */
    `class`: Option[List[String]],
    /** Published, ReviewDue, Superseded; default the valid versions. */
    status: Option[List[String]],
    /** Languages, e.g. de. */
    lang: Option[List[String]],
    /** Confirmation: the number of hits (`total`) the caller saw for this search. Required; the hold is only
      * set if the search still finds exactly this many.
      */
    expectedCount: Option[Long]
) derives JsonCodec,
      Schema

final case class CreateLegalHold(
    /** Name of the matter, e.g. "Litigation Müller GmbH". */
    name: Option[String],
    /** Why the hold is set (required). */
    reason: Option[String],
    /** Reference of the matter (Vorgang) in the legal department's systems, e.g. a case number. */
    matterReference: Option[String],
    /** Documents to put under the hold. */
    documentIds: Option[List[UUID]],
    /** A search whose hits (their documents) to put under the hold. */
    searchFilter: Option[HoldSearchFilter]
) derives JsonCodec,
      Schema

final case class AddLegalHoldDocuments(
    /** Why the documents are added (required). */
    reason: Option[String],
    documentIds: Option[List[UUID]],
    searchFilter: Option[HoldSearchFilter]
) derives JsonCodec,
      Schema

final case class ReleaseLegalHold(
    /** Why the hold is released (required). */
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class HeldDocumentView(
    documentId: UUID,
    /** Absent if the caller may not manage the document (e.g. confidential, outside their groups). */
    title: Option[String],
    /** Document (named) or Search (a hit of a confirmed search). */
    addedVia: String,
    reason: String,
    addedBy: String,
    addedAt: Instant
) derives JsonCodec,
      Schema

final case class LegalHoldView(
    id: UUID,
    name: String,
    matterReference: Option[String],
    reason: String,
    createdBy: String,
    createdAt: Instant,
    /** Active or Released. */
    status: String,
    releasedAt: Option[Instant],
    releasedBy: Option[String],
    releaseReason: Option[String],
    documentCount: Int,
    /** The documents of the hold; only in the view of a single hold. */
    documents: Option[List[HeldDocumentView]],
    /** The documents added by this request; only in the answer to a create or add request. */
    addedDocumentIds: Option[List[UUID]] = None
) derives JsonCodec,
      Schema

/** An active hold covering a document, in the document view. */
final case class LegalHoldRefView(id: UUID, name: String, matterReference: Option[String], createdAt: Instant)
    derives JsonCodec,
      Schema

/** Legal holds (business concept 7.4, US-08.1, DMS-56, ADR 0031).
  *
  * Records managers (`ManageRetention`, permission matrix row "Retention / legal hold") set a hold for a
  * matter, put documents under it (named, or the hits of a search they confirm by its hit count) and release
  * it; setting, adding and releasing require a reason and are audited. Hold-level operations need a records
  * manager assignment covering the whole tenant; every document put under a hold additionally needs
  * `ManageRetention` on the document (scope, confidentiality).
  */
object LegalHoldApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & LegalHoldService & SearchIndex

  /** Most documents one request puts under a hold, and most hits of a search put under a hold. */
  val MaxDocuments: Int = 1000

  /** Page size when a search result is read for a hold. */
  private val PageSize = 100

  private val holds  = "api" / "v1" / "legal-holds"
  private val holdId = path[UUID]("id").description("Legal hold id")

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(holds)
      .in(jsonBody[CreateLegalHold])
      .out(statusCode(StatusCode.Created).and(jsonBody[LegalHoldView]))
      .description(
        "Sets a legal hold for a matter: name and reason are required (422). Documents can be named " +
          "(`documentIds`) and/or taken from a search (`searchFilter`: the parameters of the search plus " +
          s"`expectedCount`, the hit count the caller confirmed; 409 result_changed if the search finds a different " +
          s"number now, 422 too_many_documents above $MaxDocuments hits). Documents can also be added later. " +
          "Requires the records manager role for the whole tenant and for every document."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        req =>
          for
            name     <- required(req.name, "name")
            reason   <- required(req.reason, "reason")
            subject  <- checkedSubject(p)
            searched <- ZIO.foreach(req.searchFilter)(resolve(subject, _))
            result   <- inTransaction(p) { (authz, documents, service) =>
              for
                named   <- manageable(authz, subject, documents, ids(req.documentIds))
                hits    <- manageable(authz, subject, documents, searched.getOrElse(Nil))
                created <- service.create(
                  NewLegalHold(name, req.matterReference, reason),
                  named,
                  HoldSource.Document,
                  p.userId
                )
                fromSearch <- service.addDocuments(created.id, hits, reason, HoldSource.Search, p.userId)
                hold       <- service.get(created.id)
              yield view(hold, None).copy(addedDocumentIds = Some((named ++ fromSearch).map(_.value)))
            }
          yield result
      )

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(holds)
      .in(query[Option[String]]("status").description("active (default), released or all"))
      .out(jsonBody[List[LegalHoldView]])
      .description("The legal holds of the tenant, the latest first. Requires the records manager role.")
      .tag("Legal holds")
      .serverLogic(p =>
        status =>
          for
            active <- status.getOrElse("active") match
              case "active"   => ZIO.succeed(Some(true))
              case "released" => ZIO.succeed(Some(false))
              case "all"      => ZIO.none
              case other      =>
                ZIO.fail(
                  (
                    StatusCode.BadRequest,
                    ApiError("invalid", s"unknown status '$other'; active, released or all")
                  )
                )
            _      <- checkedSubject(p)
            result <- inTransaction(p)((_, _, service) => service.list(active).map(_.map(view(_, None))))
          yield result
      )

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(holds / holdId)
      .out(jsonBody[LegalHoldView])
      .description(
        "A legal hold with its documents: who added them how and why. Titles are shown for documents the caller " +
          "may manage. Requires the records manager role."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        id =>
          for
            subject <- checkedSubject(p)
            result  <- inTransaction(p) { (_, documents, service) =>
              for
                hold   <- service.get(LegalHoldId(id))
                listed <- service.documents(hold.id)
                titled <- ZIO.foreach(listed)(d => title(subject, documents, d.documentId).map(d -> _))
              yield view(hold, Some(titled))
            }
          yield result
      )

  val addDocuments: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(holds / holdId / "documents")
      .in(jsonBody[AddLegalHoldDocuments])
      .out(jsonBody[LegalHoldView])
      .description(
        "Puts further documents under an active hold, named or from a confirmed search (as on creation); a reason " +
          "is required (422). Documents the hold covers already are skipped; `addedDocumentIds` lists the new " +
          "ones. 409 released if the hold was released."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        (id, req) =>
          for
            reason   <- required(req.reason, "reason")
            subject  <- checkedSubject(p)
            searched <- ZIO.foreach(req.searchFilter)(resolve(subject, _))
            result   <- inTransaction(p) { (authz, documents, service) =>
              for
                named <- manageable(authz, subject, documents, ids(req.documentIds))
                hits  <- manageable(authz, subject, documents, searched.getOrElse(Nil))
                _     <- ZIO.when(named.isEmpty && hits.isEmpty)(
                  ZIO.fail(LegalHoldError.Invalid(List("documentIds or searchFilter is required")))
                )
                added <- service.addDocuments(LegalHoldId(id), named, reason, HoldSource.Document, p.userId)
                fromSearch <- service.addDocuments(LegalHoldId(id), hits, reason, HoldSource.Search, p.userId)
                hold       <- service.get(LegalHoldId(id))
              yield view(hold, None).copy(addedDocumentIds = Some((added ++ fromSearch).map(_.value)))
            }
          yield result
      )

  val release: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(holds / holdId / "release")
      .in(jsonBody[ReleaseLegalHold])
      .out(jsonBody[LegalHoldView])
      .description(
        "Releases an active hold; a reason is required (422). Its documents stay listed; those no other active " +
          "hold covers are no longer under legal hold. 409 released if it was released before."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        (id, req) =>
          for
            reason <- required(req.reason, "reason")
            _      <- checkedSubject(p)
            result <- inTransaction(p)((_, _, service) =>
              service.release(LegalHoldId(id), reason, p.userId).map(view(_, None))
            )
          yield result
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(create, list, get, addDocuments, release)

  private type Failure = LegalHoldError | DocumentError | AccessDenied

  /** A required text field; 422 if it is missing or blank. */
  private def required(value: Option[String], field: String): IO[(StatusCode, ApiError), String] =
    ZIO
      .fromOption(value.map(_.trim).filter(_.nonEmpty))
      .orElseFail(toResponse(LegalHoldError.Invalid(List(s"$field is required"))))

  /** The caller's subject, if they may manage legal holds of the whole tenant (403 otherwise). */
  private def checkedSubject(p: Principal): ZIO[Env, (StatusCode, ApiError), Subject] =
    for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      subject <- db
        .transact(p.tenantId)(
          authz.subject(p).tap(s => authz.check(s, Action.ManageRetention, Resource(p.tenantId)))
        )
        .mapError {
          case e: AccessDenied => DocumentApi.toResponse(e)
          case e: DbError      => DocumentApi.toResponse(e)
        }
    yield subject

  /** The documents of a confirmed search: re-runs the search as the caller (so it only covers documents they
    * may read) and requires the confirmed hit count.
    */
  private def resolve(
      subject: Subject,
      filter: HoldSearchFilter
  ): ZIO[SearchIndex, (StatusCode, ApiError), List[DocumentId]] =
    def values(params: Option[List[String]]) =
      params.getOrElse(Nil).flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).distinct
    def page(index: SearchIndex, statuses: List[DocumentStatus], n: Int) =
      index
        .search(
          subject,
          SearchQuery(filter.q, values(filter.`class`), statuses, values(filter.lang), n, PageSize)
        )
        .mapError(searchResponse)
    for
      expected <- ZIO
        .fromOption(filter.expectedCount)
        .orElseFail(toResponse(LegalHoldError.Invalid(List("searchFilter.expectedCount is required"))))
      statuses <- ZIO.foreach(values(filter.status))(s =>
        ZIO
          .fromOption(Try(DocumentStatus.valueOf(s)).toOption)
          .orElseFail(toResponse(LegalHoldError.Invalid(List(s"unknown status '$s'"))))
      )
      index <- ZIO.service[SearchIndex]
      first <- page(index, statuses, 1)
      _     <- ZIO.when(first.total > MaxDocuments)(
        ZIO.fail(
          (
            StatusCode.UnprocessableEntity,
            ApiError(
              "too_many_documents",
              s"The search finds ${first.total} hits; at most $MaxDocuments can be put under a hold at once. " +
                "Narrow the search."
            )
          )
        )
      )
      _ <- ZIO.when(first.total != expected)(
        ZIO.fail(
          (
            StatusCode.Conflict,
            ApiError(
              "result_changed",
              s"The search finds ${first.total} hits now, not $expected. Check the result and confirm again."
            )
          )
        )
      )
      pages = ((first.total + PageSize - 1) / PageSize).toInt
      rest <- ZIO.foreach((2 to pages).toList)(n => page(index, statuses, n))
    yield (first :: rest).flatMap(_.hits.map(_.documentId)).distinct

  private def ids(documentIds: Option[List[UUID]]): List[DocumentId] =
    documentIds.getOrElse(Nil).map(DocumentId(_))

  /** The documents if the caller may manage every one of them (404 for an unknown, 403 for another). */
  private def manageable(
      authz: Authorizer,
      subject: Subject,
      documents: DocumentService,
      ids: List[DocumentId]
  ): IO[Failure, List[DocumentId]] =
    val distinct = ids.distinct
    if distinct.size > MaxDocuments then
      ZIO.fail(
        LegalHoldError.Invalid(List(s"at most $MaxDocuments documents can be put under a hold at once"))
      )
    else
      ZIO.foreach(distinct)(id =>
        for
          details <- documents.get(id)
          latest = details.versions.last
          _ <- authz.check(
            subject,
            Action.ManageRetention,
            DocumentApi.resource(subject.tenantId, details, latest)
          )
        yield id
      )

  private def title(
      subject: Subject,
      documents: DocumentService,
      id: DocumentId
  ): IO[DocumentError, Option[String]] =
    for
      details <- documents.get(id)
      now     <- Clock.instant
      allowed = dms.authz.Policy
        .decide(
          subject,
          Action.ManageRetention,
          DocumentApi.resource(subject.tenantId, details, details.versions.last),
          now
        )
        .isPermitted
    yield Option.when(allowed)(details.document.title)

  private def view(hold: LegalHold, documents: Option[List[(HeldDocument, Option[String])]]): LegalHoldView =
    LegalHoldView(
      hold.id.value,
      hold.name,
      hold.matterReference,
      hold.reason,
      hold.createdBy.value,
      hold.createdAt,
      if hold.isActive then "Active" else "Released",
      hold.release.map(_.at),
      hold.release.map(_.by.value),
      hold.release.map(_.reason),
      hold.documentCount,
      documents.map(
        _.map((d, title) =>
          HeldDocumentView(d.documentId.value, title, d.source.toString, d.reason, d.addedBy.value, d.addedAt)
        )
      )
    )

  /** The reference of an active hold in the document view. */
  private[app] def refView(hold: LegalHold): LegalHoldRefView =
    LegalHoldRefView(hold.id.value, hold.name, hold.matterReference, hold.createdAt)

  private def inTransaction[A](principal: Principal)(
      f: (Authorizer, DocumentService, LegalHoldService) => ZIO[Any, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db        <- ZIO.service[Db]
      authz     <- ZIO.service[Authorizer]
      documents <- ZIO.service[DocumentService]
      service   <- ZIO.service[LegalHoldService]
      result    <- db.transact(principal.tenantId)(f(authz, documents, service))
    yield result).mapError {
      case e: LegalHoldError => toResponse(e)
      case e: AccessDenied   => DocumentApi.toResponse(e)
      case e: DbError        => DocumentApi.toResponse(e)
      case e: DocumentError  => DocumentApi.toResponse(e)
    }

  private def searchResponse(error: SearchError): (StatusCode, ApiError) = SearchApi.toResponse(error)

  def toResponse(error: LegalHoldError): (StatusCode, ApiError) = error match
    case e @ LegalHoldError.Invalid(_) => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case LegalHoldError.NotFound(m)    => (StatusCode.NotFound, ApiError("not_found", m))
    case LegalHoldError.Released(m)    => (StatusCode.Conflict, ApiError("released", m))
    case LegalHoldError.Database(e)    => DocumentApi.toResponse(e)

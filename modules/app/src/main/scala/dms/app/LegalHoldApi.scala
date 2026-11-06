package dms.app

import dms.archive.*
import dms.archive.core.{LegalHoldService, LegalHoldStore}
import dms.authz.{AccessDenied, Action, Authorizer, Policy, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.{DocumentStatus, Versioning}
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import dms.search.{SearchError, SearchHit, SearchQuery}
import dms.search.core.SearchIndex
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    expectedCount: Option[Long],
    /** Exact confirmation (DMS-106): the `fingerprint` of the search preview the caller reviewed. If given,
      * the hold is only set if the search still finds exactly the same documents (409 result_changed
      * otherwise).
      */
    expectedFingerprint: Option[String] = None
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

final case class RemoveLegalHoldDocument(
    /** Why the document is taken out of the hold (required). */
    reason: Option[String]
) derives JsonCodec,
      Schema

/** A document a search would put under a hold. */
final case class HoldCandidateView(
    documentId: UUID,
    /** Title of the hit; the search only finds documents the caller may read. */
    title: String,
    documentNumber: Option[String],
    /** False if the caller is not cleared for every version that stands for the document. The hold can be set
      * all the same (ADR 0082), but only someone cleared can take the document out of it again.
      */
    manageable: Boolean,
    /** True if an active legal hold covers the document already. */
    underLegalHold: Boolean
) derives JsonCodec,
      Schema

/** The documents a search would put under a hold, for the records manager to review before confirming. */
final case class HoldSearchPreviewView(
    /** Number of hits (versions) of the search, the value for `expectedCount`. */
    total: Long,
    /** The distinct documents of all hits, in hit order. */
    documents: List[HoldCandidateView],
    /** Identifies exactly these documents; the value for `expectedFingerprint`. */
    fingerprint: String
) derives JsonCodec,
      Schema

final case class ReleaseLegalHold(
    /** Why the hold is released (required). */
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class HeldDocumentView(
    documentId: UUID,
    /** The title the caller may know: the document's if they are cleared for every version, else the title of
      * a version they are cleared for (ADR 0082). Absent if there is none: nothing of the document is shown.
      */
    title: Option[String],
    /** Document (named) or Search (a hit of a confirmed search). */
    addedVia: String,
    reason: String,
    addedBy: String,
    addedAt: Instant,
    /** Whether the caller may take the document out of a hold: this needs clearance, setting a hold does not.
      */
    removable: Boolean,
    /** Set if the document was removed from the hold (DMS-106); it then no longer counts as covered by it. */
    removedAt: Option[Instant] = None,
    removedBy: Option[String] = None,
    removalReason: Option[String] = None
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
    /** Number of documents the hold covers now. */
    documentCount: Int,
    /** The documents of the hold, including removed ones (history); only in the view of a single hold. */
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
  * it; setting, adding and releasing require a reason and are audited. Every operation needs a records
  * manager assignment covering the whole tenant.
  *
  * A legal hold is always possible (DMS-156, ADR 0082): it reveals nothing and protects at once, so putting a
  * document under a hold needs no clearance for any of its versions. What the caller is shown still follows
  * the clearance: a title only of a version they are cleared for. Taking a single document out of a hold ends
  * its protection, so that needs `ManageRetention` on every governing version (ADR 0072).
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
          "Requires the records manager role for the whole tenant; no clearance for the documents is needed, " +
          "and the response names them by id only."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        req =>
          for
            name     <- required(req.name, "name")
            reason   <- required(req.reason, "reason")
            subject  <- checkedSubject(p)
            searched <- ZIO.foreach(req.searchFilter)(resolve(subject, _))
            result   <- inTransaction(p) { (_, documents, service) =>
              for
                named   <- existing(documents, ids(req.documentIds))
                hits    <- existing(documents, searched.getOrElse(Nil))
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
        "A legal hold with its documents: who added them how and why. A title is shown only if the caller is " +
          "cleared for the version it comes from, and `removable` says whether they may take the document out. " +
          "Requires the records manager role."
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
                titled <- ZIO.foreach(listed)(d => shown(subject, documents, d.documentId).map(d -> _))
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
          "ones. 409 released if the hold was released. No clearance for the documents is needed."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        (id, req) =>
          for
            reason   <- required(req.reason, "reason")
            subject  <- checkedSubject(p)
            searched <- ZIO.foreach(req.searchFilter)(resolve(subject, _))
            result   <- inTransaction(p) { (_, documents, service) =>
              for
                named <- existing(documents, ids(req.documentIds))
                hits  <- existing(documents, searched.getOrElse(Nil))
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

  val preview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(holds / "search-preview")
      .in(jsonBody[HoldSearchFilter])
      .out(jsonBody[HoldSearchPreviewView])
      .description(
        "The documents a search would put under a hold (DMS-106): the search runs as the caller, as when the " +
          "hold is set; `expectedCount` is ignored. Returns the hit count, the distinct documents (the title of " +
          "the hit, a version the caller may read; whether they could take the document out of a hold again; " +
          "whether a hold covers it already) and a `fingerprint` to confirm exactly these documents with " +
          s"`expectedFingerprint`. 422 too_many_documents above $MaxDocuments hits. " +
          "Requires the records manager role for the whole tenant."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        filter =>
          for
            subject <- checkedSubject(p)
            found   <- hits(subject, filter)
            (total, documentHits) = found
            result <- inTransaction(p) { (_, documents, _) =>
              for
                held <- LegalHoldStore
                  .heldAmong(documentHits.map(_.documentId).toSet)
                  .mapError(LegalHoldError.Database(_))
                candidates <- ZIO.foreach(documentHits)(h =>
                  manages(subject, documents, h.documentId).map(m =>
                    HoldCandidateView(
                      h.documentId.value,
                      h.title,
                      h.documentNumber,
                      m,
                      held.contains(h.documentId)
                    )
                  )
                )
              yield HoldSearchPreviewView(total, candidates, fingerprint(documentHits.map(_.documentId)))
            }
          yield result
      )

  val removeDocument: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(holds / holdId / "documents" / path[UUID]("documentId").description("Document id") / "remove")
      .in(jsonBody[RemoveLegalHoldDocument])
      .out(jsonBody[LegalHoldView])
      .description(
        "Takes a single document out of an active hold (DMS-106), e.g. one added by mistake; a reason is required " +
          "(422). The entry stays in the hold's history with the removal; the document is no longer under legal " +
          "hold unless another active hold covers it. 404 if the hold does not cover the document, 409 released " +
          "if the hold was released. Requires the records manager role for the whole tenant and clearance for " +
          "every version that stands for the document."
      )
      .tag("Legal holds")
      .serverLogic(p =>
        (id, documentId, req) =>
          for
            reason  <- required(req.reason, "reason")
            subject <- checkedSubject(p)
            result  <- inTransaction(p) { (authz, documents, service) =>
              for
                _       <- checkManageable(authz, subject, documents, DocumentId(documentId))
                changed <- service.removeDocument(LegalHoldId(id), DocumentId(documentId), reason, p.userId)
              yield view(changed, None)
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

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(create, list, preview, get, addDocuments, removeDocument, release)

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
    * may read) and requires the confirmed hit count and, if given, the confirmed set of documents.
    */
  private def resolve(
      subject: Subject,
      filter: HoldSearchFilter
  ): ZIO[SearchIndex, (StatusCode, ApiError), List[DocumentId]] =
    for
      expected <- ZIO
        .fromOption(filter.expectedCount)
        .orElseFail(toResponse(LegalHoldError.Invalid(List("searchFilter.expectedCount is required"))))
      found <- hits(subject, filter)
      (total, documents) = found
      _ <- ZIO.when(total != expected)(
        ZIO.fail(
          (
            StatusCode.Conflict,
            ApiError(
              "result_changed",
              s"The search finds $total hits now, not $expected. Check the result and confirm again."
            )
          )
        )
      )
      ids = documents.map(_.documentId)
      _ <- ZIO.foreachDiscard(filter.expectedFingerprint.filterNot(_ == fingerprint(ids)))(_ =>
        ZIO.fail(
          (
            StatusCode.Conflict,
            ApiError(
              "result_changed",
              "The search finds other documents now than the ones you reviewed. Check the result and confirm again."
            )
          )
        )
      )
    yield ids

  /** The hit count of a search run as the caller and the first hit of each document, in hit order; 422 above
    * [[MaxDocuments]] hits.
    */
  private def hits(
      subject: Subject,
      filter: HoldSearchFilter
  ): ZIO[SearchIndex, (StatusCode, ApiError), (Long, List[SearchHit])] =
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
      pages = ((first.total + PageSize - 1) / PageSize).toInt
      rest <- ZIO.foreach((2 to pages).toList)(n => page(index, statuses, n))
      all = (first :: rest).flatMap(_.hits)
    yield (first.total, all.distinctBy(_.documentId))

  /** Identifies a set of documents independent of their order: SHA-256 (hex) over the sorted ids. */
  def fingerprint(documents: List[DocumentId]): String =
    val text = documents.map(_.value.toString).distinct.sorted.mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def ids(documentIds: Option[List[UUID]]): List[DocumentId] =
    documentIds.getOrElse(Nil).map(DocumentId(_))

  /** The documents to put under a hold, if they all exist (404 otherwise). The caller's role is checked, and
    * a hold needs nothing more (ADR 0082).
    */
  private def existing(documents: DocumentService, ids: List[DocumentId]): IO[Failure, List[DocumentId]] =
    val distinct = ids.distinct
    if distinct.size > MaxDocuments then
      ZIO.fail(
        LegalHoldError.Invalid(List(s"at most $MaxDocuments documents can be put under a hold at once"))
      )
    else ZIO.foreach(distinct)(id => documents.get(id).as(id))

  /** That the caller may take the document out of a hold (404 for an unknown, 403 for another document). */
  private def checkManageable(
      authz: Authorizer,
      subject: Subject,
      documents: DocumentService,
      id: DocumentId
  ): IO[Failure, Unit] =
    documents
      .get(id)
      .flatMap(details =>
        ZIO.foreachDiscard(DocumentApi.governing(subject.tenantId, details))(
          authz.check(subject, Action.ManageRetention, _)
        )
      )

  /** What the caller is shown of a held document: a title and whether they may take it out of a hold. */
  private def shown(
      subject: Subject,
      documents: DocumentService,
      id: DocumentId
  ): IO[DocumentError, (Option[String], Boolean)] =
    for
      details <- documents.get(id)
      title   <- titleFor(subject, details)
      allowed <- permits(subject, details)
    yield (title, allowed)

  /** The document's title for someone cleared for all of it. For anyone else, the title a version they are
    * cleared for states itself: the document's title may come from another version, also from a draft saved
    * before ADR 0074. Without such a version, none.
    */
  private def titleFor(subject: Subject, details: DocumentDetails): UIO[Option[String]] =
    Clock.instant.map { now =>
      val cleared = details.versions.filter(v =>
        Policy
          .decide(subject, Action.ManageRetention, DocumentApi.resource(subject.tenantId, details, v), now)
          .isPermitted
      )
      if cleared.size == details.versions.size then Some(details.document.title)
      else Versioning.naming(cleared).flatMap(DocumentRenditions.ownTitle)
    }

  /** Whether the caller may take the document out of a hold (without failing). */
  private def manages(
      subject: Subject,
      documents: DocumentService,
      id: DocumentId
  ): IO[DocumentError, Boolean] =
    documents.get(id).flatMap(permits(subject, _))

  private def permits(subject: Subject, details: DocumentDetails): UIO[Boolean] =
    Clock.instant.map(now =>
      DocumentApi
        .governing(subject.tenantId, details)
        .forall(Policy.decide(subject, Action.ManageRetention, _, now).isPermitted)
    )

  private def view(
      hold: LegalHold,
      documents: Option[List[(HeldDocument, (Option[String], Boolean))]]
  ): LegalHoldView =
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
        _.map { case (d, (title, removable)) =>
          HeldDocumentView(
            d.documentId.value,
            title,
            d.source.toString,
            d.reason,
            d.addedBy.value,
            d.addedAt,
            removable,
            d.removal.map(_.at),
            d.removal.map(_.by.value),
            d.removal.map(_.reason)
          )
        }
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

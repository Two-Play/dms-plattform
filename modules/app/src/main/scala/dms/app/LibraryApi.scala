package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, DocumentResource}
import dms.db.{Db, DbError}
import dms.documents.Validity
import dms.documents.core.*
import dms.identity.{Authenticator, Principal}
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}
import java.util.UUID

/** A document of the library (DMS-119): the version of it that is valid now. */
final case class LibraryItemView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    /** The document number (DMS-113), if the document has one. */
    documentNumber: Option[String],
    /** Version number (index), e.g. `2.1`. */
    version: String,
    /** Published, or ReviewDue (still valid while its periodic review is due). */
    status: String,
    classPath: List[String],
    /** Label of the document's class (the last key of `classPath`). */
    classLabel: String,
    /** The issuing unit: the organizational unit of the version. */
    organizationalUnit: Option[String],
    /** Public, Internal, Confidential or StrictlyConfidential. */
    confidentiality: String,
    tags: List[String],
    /** Validity scope; empty: unrestricted. A document valid in several scopes is listed once per scope. */
    scope: String,
    /** Short description from the field `summary`, at most 300 characters. */
    summary: Option[String],
    validFrom: Option[LocalDate],
    /** When the version became valid: 00:00 of its valid-from date in the tenant's time zone, or its
      * publication if that was later.
      */
    validSince: Instant,
    /** When the version was published (last change for readers). */
    publishedAt: Instant,
    /** The document's first version became valid on or after `since`. */
    isNew: Boolean,
    /** The document existed before `since` and this version became valid on or after it. */
    changed: Boolean,
    requiresAcknowledgement: Boolean,
    /** The caller is asked to acknowledge this version and has not done so yet. */
    acknowledgementPending: Boolean
) derives JsonCodec,
      Schema

final case class LibraryFacetView(
    value: String,
    /** Display label, for classes. */
    label: Option[String],
    count: Int
) derives JsonCodec,
      Schema

/** Facet counts over the documents the caller may read. Each facet applies every filter except its own. */
final case class LibraryFacetsView(
    /** By class of the document (the last class key). */
    `class`: List[LibraryFacetView],
    /** By issuing unit (organizational unit). */
    unit: List[LibraryFacetView],
    tag: List[LibraryFacetView],
    confidentiality: List[LibraryFacetView],
    /** Documents new or changed since `since`. */
    changed: Int,
    /** Documents the caller has yet to acknowledge. */
    pending: Int
) derives JsonCodec,
      Schema

final case class LibraryView(
    /** Number of matching documents (all pages). */
    total: Int,
    page: Int,
    size: Int,
    sort: String,
    order: String,
    /** The reference date of `isNew` and `changed`, read in the tenant's time zone. */
    since: LocalDate,
    timeZone: String,
    items: List[LibraryItemView],
    facets: LibraryFacetsView
) derives JsonCodec,
      Schema

/** The library (DMS-119, ADR 0043): the currently valid versions the caller may read, with filters, facets,
  * sorting, "new" and "changed since" markers and the caller's open acknowledgements.
  *
  * Read from PostgreSQL, the authoritative store of validity (ADR 0021, 0026), like the "valid at" list
  * (DMS-53) and the stable addresses (DMS-58); the search index is not involved. A version is listed only if
  * the caller may read it with `ReadDocument`, the same decision that opens the document: readers of the
  * class and unit, confidential versions only for author, owner and members of an allowed group (ADR 0032).
  * Facets and totals count only listed versions.
  */
object LibraryApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow & AcknowledgementService &
    DocumentClassService

  /** Largest page. */
  val MaxSize = 200

  val DefaultSize = 24

  private def list(name: String, description: String) =
    query[List[String]](name).description(description + " Repeat or separate with commas.")

  val library: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "library")
      .in(
        query[Option[String]]("q").description(
          "Free text: every word must occur in title, document number, description, class, unit, tags or scope " +
            "(case-insensitive)."
        )
      )
      .in(list("class", "Class keys; a class includes its subclasses."))
      .in(list("unit", "Issuing units (organizational units)."))
      .in(list("tag", "Tags; a document matches if it has one of them."))
      .in(list("confidentiality", "Public, Internal, Confidential, StrictlyConfidential."))
      .in(
        query[Option[LocalDate]]("since").description(
          s"Reference date (YYYY-MM-DD, tenant time zone) of `isNew` and `changed`; default ${Library.DefaultChangeWindowDays} days ago."
        )
      )
      .in(query[Option[Boolean]]("changed").description("Only documents new or changed since `since`."))
      .in(query[Option[Boolean]]("pending").description("Only documents the caller has yet to acknowledge."))
      .in(
        query[Option[String]]("sort").description(
          "title (default), number, validFrom or lastChanged (publication of the valid version)."
        )
      )
      .in(
        query[Option[String]]("order").description(
          "asc or desc; default ascending for title and number, descending for dates."
        )
      )
      .in(query[Option[Int]]("page").description("1-based page, default 1"))
      .in(query[Option[Int]]("size").description(s"Documents per page, 1 to $MaxSize, default $DefaultSize"))
      .out(jsonBody[LibraryView])
      .description(
        "The library: the versions valid now that the caller may read, one per document and validity scope, " +
          "filtered by free text, class, issuing unit, tag and confidentiality, with facet counts, markers for " +
          "documents new or changed since a date and the caller's open acknowledgements."
      )
      .tag("Library")
      .serverLogic(p =>
        (q, classes, units, tags, confidentiality, since, changed, pending, sort, order, page, size) =>
          for
            sortBy <- ZIO
              .fromOption(
                sort.map(_.trim).filter(_.nonEmpty).fold(Some(LibrarySort.Title))(LibrarySort.parse)
              )
              .orElseFail(
                invalid(s"unknown sort '${sort.getOrElse("")}'; use title, number, validFrom or lastChanged")
              )
            ascending <- order.map(_.trim.toLowerCase) match
              case None | Some("") => ZIO.succeed(sortBy.defaultAscending)
              case Some("asc")     => ZIO.succeed(true)
              case Some("desc")    => ZIO.succeed(false)
              case Some(other)     => ZIO.fail(invalid(s"unknown order '$other'; use asc or desc"))
            pageNo <- ZIO.succeed(page.getOrElse(1)).filterOrFail(_ >= 1)(invalid("page must be at least 1"))
            pageSize <- ZIO
              .succeed(size.getOrElse(DefaultSize))
              .filterOrFail(s => s >= 1 && s <= MaxSize)(invalid(s"size must be between 1 and $MaxSize"))
            filter = LibraryFilter(
              q.map(_.trim).filter(_.nonEmpty),
              values(classes),
              values(units),
              values(tags),
              values(confidentiality),
              changed.contains(true),
              pending.contains(true)
            )
            result <- load(p, since).map { (items, labels, reference, zone) =>
              val matching = Library.sort(Library.filter(items, filter), sortBy, ascending, zone)
              LibraryView(
                matching.size,
                pageNo,
                pageSize,
                sortName(sortBy),
                if ascending then "asc" else "desc",
                reference,
                zone.getId,
                Library.page(matching, pageNo, pageSize).map(view),
                facetsView(Library.facets(items, filter, labels))
              )
            }
          yield result
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(library)

  private def sortName(s: LibrarySort): String = s match
    case LibrarySort.Title       => "title"
    case LibrarySort.Number      => "number"
    case LibrarySort.ValidFrom   => "validFrom"
    case LibrarySort.LastChanged => "lastChanged"

  /** Values of a list parameter, repeated or comma-separated, trimmed. */
  private def values(raw: List[String]): Set[String] =
    raw.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).toSet

  private def invalid(message: String): (StatusCode, ApiError) =
    (StatusCode.UnprocessableEntity, ApiError("invalid", message))

  private type Failure = DocumentError | AccessDenied | WorkflowError | AcknowledgementError | DbError

  /** The readable valid versions as library items, the class labels by key, the reference date and the
    * tenant's time zone.
    */
  private def load(p: Principal, since: Option[LocalDate]) =
    inTransaction(p) { (ctx, acks, classes) =>
      for
        now      <- Clock.instant
        zone     <- ctx.service.tenantZone
        valid    <- ctx.service.currentlyValid
        readable <- ZIO.filter(valid)(v =>
          ctx.allowed(
            Action.ReadDocument,
            DocumentApi.versionResource(
              p.tenantId,
              v.classPath,
              v.document.ownerId,
              v.status,
              v.createdBy,
              v.metadata,
              v.releasedBy
            )
          )
        )
        acknowledged <- acks.acknowledgementsOf(p.userId).map(_.map(_.versionId).toSet)
        labels       <- classes.listClasses
          .mapError(DocumentService.metadataError)
          .map(_.map(c => c.key.value -> c.label).toMap)
        reference = since.getOrElse(LocalDate.ofInstant(now, zone).minusDays(Library.DefaultChangeWindowDays))
        boundary  = Library.changeBoundary(reference, zone)
      yield (
        readable.map { v =>
          val published = v.publishedAt.getOrElse(v.document.createdAt)
          val first     = v.firstPublishedAt.getOrElse(published)
          val since     = Validity.effectiveStart(v.validFrom, published, zone)
          val values    = v.metadata
          val classKey  = v.classPath.lastOption.getOrElse("")
          LibraryItem(
            v.document.id.value,
            v.versionId,
            Library.title(values).getOrElse(v.document.title),
            v.document.number,
            v.label,
            v.status.toString,
            v.classPath,
            labels.getOrElse(classKey, classKey),
            DocumentResource.organizationalUnit(values),
            DocumentResource.confidentiality(values).toString,
            Library.tags(values),
            v.scope,
            Library.summary(values),
            v.validFrom,
            since,
            published,
            Library.isNew(first, boundary),
            Library.isChanged(first, since, boundary),
            v.requiresAcknowledgement,
            v.requiresAcknowledgement && !acknowledged.contains(v.versionId)
          )
        },
        labels,
        reference,
        zone
      )
    }

  private def view(i: LibraryItem) =
    LibraryItemView(
      i.documentId,
      i.versionId,
      i.title,
      i.documentNumber,
      i.version,
      i.status,
      i.classPath,
      i.classLabel,
      i.organizationalUnit,
      i.confidentiality,
      i.tags,
      i.scope,
      i.summary,
      i.validFrom,
      i.validSince,
      i.publishedAt,
      i.isNew,
      i.changed,
      i.requiresAcknowledgement,
      i.acknowledgementPending
    )

  private def facetsView(f: LibraryFacets) =
    def views(fs: List[LibraryFacet]) = fs.map(x => LibraryFacetView(x.value, x.label, x.count))
    LibraryFacetsView(
      views(f.classes),
      views(f.units),
      views(f.tags),
      views(f.confidentiality),
      f.changed,
      f.pending
    )

  private def inTransaction[A](principal: Principal)(
      f: (DocumentApi.Context, AcknowledgementService, DocumentClassService) => ZIO[Any, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      acks     <- ZIO.service[AcknowledgementService]
      classes  <- ZIO.service[DocumentClassService]
      result   <- db.transact(principal.tenantId)(
        authz
          .subject(principal)
          .flatMap(subject => f(DocumentApi.Context(service, workflow, authz, subject), acks, classes))
      )
    yield result).mapError {
      case e: WorkflowError        => WorkflowApi.toResponse(e)
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: DocumentError        => DocumentApi.toResponse(e)
      case e: AcknowledgementError => AcknowledgementApi.toResponse(e)
    }

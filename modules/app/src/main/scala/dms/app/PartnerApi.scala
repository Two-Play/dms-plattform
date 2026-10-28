package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Resource}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.documents.metadata.MetadataValue
import dms.identity.{Authenticator, Principal}
import dms.integration.partners.*
import dms.kernel.DocumentId
import dms.templates.{ReferenceField, ReferenceSource}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.util.UUID

/** An entry of the partner master. */
final case class PartnerView(
    /** Identifier in the partner master (partner number). */
    id: String,
    name: String,
    street: Option[String],
    postalCode: Option[String],
    city: Option[String],
    /** ISO 3166-1 alpha-2 code. */
    country: Option[String],
    /** The address in one line, as taken over by the `address` attribute. */
    address: String,
    vatId: Option[String]
) derives JsonCodec,
      Schema

final case class ApplyReference(
    /** Identifier of the picked entry, e.g. a partner number from `GET /api/v1/partners`. */
    entryId: String
) derives JsonCodec,
      Schema

/** The partner master (US-03.1, ADR 0023): search, and taking a picked partner over into a draft.
  *
  * Taken-over values are a snapshot with origin `System("partner-master")`; the identifier is stored as
  * source reference. They cannot be changed with `PUT /draft` (422 `not_editable`), only replaced by picking
  * another partner or removed. When the partner master is unavailable the endpoints answer 503
  * `partner_directory_unavailable` (502 `partner_directory_invalid_response` for a broken answer); saving the
  * draft does not depend on it. Calls to the partner master run outside database transactions.
  */
object PartnerApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & PartnerDirectory

  private type Failure = AccessDenied | DbError | DocumentError | PartnerDirectoryError

  private val referencePath =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "references" /
      path[String]("key").description("Key of the template's reference field, e.g. `contractPartner`")

  val search: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "partners")
      .in(
        query[String]("q").description(
          s"Part of the name or identifier, at least ${PartnerDirectory.MinQueryLength} characters"
        )
      )
      .in(
        query[Option[Int]]("limit").description(
          s"At most this many hits (1 to ${PartnerDirectory.MaxResults}, default 20)"
        )
      )
      .out(jsonBody[List[PartnerView]])
      .description(
        "Searches the partner master by name and identifier. Requires access to the template catalog (every " +
          "role that creates documents). 503 when the partner master is not reachable."
      )
      .tag("Partners")
      .serverLogic(p =>
        (q, limit) =>
          val text = q.trim
          for
            _ <- ZIO.when(text.length < PartnerDirectory.MinQueryLength)(
              ZIO.fail(
                (
                  StatusCode.UnprocessableEntity,
                  ApiError(
                    "invalid",
                    s"Enter at least ${PartnerDirectory.MinQueryLength} characters to search."
                  )
                )
              )
            )
            _ <- ZIO.when(limit.exists(l => l < 1 || l > PartnerDirectory.MaxResults))(
              ZIO.fail(
                (
                  StatusCode.UnprocessableEntity,
                  ApiError("invalid", s"limit must be between 1 and ${PartnerDirectory.MaxResults}")
                )
              )
            )
            _    <- authorizeSearch(p)
            hits <- ZIO
              .serviceWithZIO[PartnerDirectory](_.search(p.tenantId, text, limit.getOrElse(20)))
              .mapError(toResponse)
          yield hits.map(view)
      )

  val applyReference: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(referencePath)
      .in(jsonBody[ApplyReference])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Takes the picked entry over into the draft: the mapped attributes become field values with origin " +
          "System (`partner-master`), replacing those of a previously picked entry; saved as the next working " +
          "state. 404 for an unknown reference field, 422 `unknown_entry` if the partner master does not know " +
          "the entry, 503 when it is not reachable (the draft stays as it is)."
      )
      .tag("Partners")
      .serverLogic(p =>
        (id, key, req) =>
          (for
            // Check permission and reference first, then call the partner master outside any transaction.
            reference <- inTransaction(p)(ctx => ctx.reference(DocumentId(id), key))
            partner   <- ZIO
              .serviceWithZIO[PartnerDirectory](_.get(p.tenantId, req.entryId.trim))
              .someOrFail(UnknownEntry(req.entryId.trim))
            updated <- inTransaction(p)(ctx => ctx.replace(DocumentId(id), key, Some(partner)))
            _       <- ZIO.logInfo(s"Partner ${partner.id} taken over into ${reference.key} of document $id")
          yield versionView(updated)).mapError(toResponse)
      )

  val removeReference: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(referencePath)
      .out(jsonBody[DocumentVersionView])
      .description(
        "Removes the values taken over for the reference field from the draft (the partner master is not called)."
      )
      .tag("Partners")
      .serverLogic(p =>
        (id, key) =>
          inTransaction(p)(ctx => ctx.replace(DocumentId(id), key, None))
            .map(versionView)
            .mapError(toResponse)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(search, applyReference, removeReference)

  /** Field values a reference field takes over from `partner`; attributes without a value are left out. */
  def referenceValues(reference: ReferenceField, partner: Partner): Map[String, FieldEntry] =
    reference.fields.flatMap { m =>
      partner
        .attribute(m.attribute)
        .map(v => m.field -> FieldEntry(MetadataValue.Text(v), ValueOrigin.System(reference.source.key)))
    }.toMap

  private final class Context(service: DocumentService, authz: Authorizer, principal: Principal):
    /** The reference field `key` of the open draft, if the caller may edit it. */
    def reference(id: DocumentId, key: String): IO[Failure, ReferenceField] =
      draft(id).flatMap((open, _) => referenceOf(open, key))

    /** Replaces the values of reference field `key` with those taken over from `partner` (or removes them).
      */
    def replace(id: DocumentId, key: String, partner: Option[Partner]): IO[Failure, DocumentVersion] =
      for
        (open, _) <- draft(id)
        reference <- referenceOf(open, key)
        kept  = open.values.filter((field, e) => entered(e.origin) && !reference.fieldKeys.contains(field))
        taken = partner.fold(Map.empty[String, FieldEntry])(referenceValues(reference, _))
        updated <- service.updateDraft(id, DraftChange(None, kept ++ taken, None), principal.userId)
      yield updated

    private def draft(id: DocumentId): IO[Failure, (DocumentVersion, DocumentDetails)] =
      for
        details <- service.get(id)
        open    <- ZIO
          .fromOption(details.open)
          .orElseFail(DocumentError.Conflict(s"document ${id.value} has no open version"))
        subject <- authz.subject(principal)
        _ <- authz.check(subject, Action.EditDraft, DocumentApi.resource(principal.tenantId, details, open))
      yield (open, details)

    private def referenceOf(open: DocumentVersion, key: String): IO[DocumentError, ReferenceField] =
      ZIO
        .fromOption(open.content.references.find(_.key == key))
        .orElseFail(DocumentError.NotFound(s"the template of this document has no reference field '$key'"))
        .filterOrFail(_.source == ReferenceSource.PartnerMaster)(
          DocumentError.Invalid(List(s"reference field '$key' does not take values from the partner master"))
        )

  /** Values that are entered (by a person or a business system), not those the platform sets or computes. */
  private def entered(origin: ValueOrigin): Boolean = origin match
    case ValueOrigin.Manual    => true
    case ValueOrigin.System(_) => origin != ValueOrigin.Platform
    case ValueOrigin.Computed  => false

  private def inTransaction[A](principal: Principal)(
      f: Context => IO[Failure, A]
  ): ZIO[Db & Authorizer & DocumentService, Failure, A] =
    for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      service <- ZIO.service[DocumentService]
      result  <- db.transact(principal.tenantId)(f(Context(service, authz, principal)))
    yield result

  private def authorizeSearch(p: Principal): ZIO[Db & Authorizer, (StatusCode, ApiError), Unit] =
    (for
      db    <- ZIO.service[Db]
      authz <- ZIO.service[Authorizer]
      _     <- db.transact(p.tenantId)(
        authz.subject(p).flatMap(s => authz.check(s, Action.ReadTemplateCatalog, Resource(p.tenantId)))
      )
    yield ()).mapError(toResponse)

  /** The partner master does not know the picked entry. */
  final case class UnknownEntry(id: String)

  private def view(p: Partner): PartnerView =
    PartnerView(
      p.id,
      p.name,
      p.address.street,
      p.address.postalCode,
      p.address.city,
      p.address.country,
      p.address.singleLine,
      p.vatId
    )

  private def versionView(v: DocumentVersion): DocumentVersionView = DocumentApi.versionView(v)

  /** Message for authors: what happened and that the draft can still be saved. */
  val UnavailableHint = "You can save the draft without it and pick the partner later."

  def toResponse(error: Failure | UnknownEntry): (StatusCode, ApiError) = error match
    case PartnerDirectoryError.Unavailable(reason) =>
      (
        StatusCode.ServiceUnavailable,
        ApiError(
          "partner_directory_unavailable",
          s"The partner master is not reachable: $reason. $UnavailableHint"
        )
      )
    case PartnerDirectoryError.InvalidResponse(reason) =>
      (
        StatusCode.BadGateway,
        ApiError(
          "partner_directory_invalid_response",
          s"The partner master sent an answer that cannot be used: $reason. $UnavailableHint"
        )
      )
    case UnknownEntry(id) =>
      (StatusCode.UnprocessableEntity, ApiError("unknown_entry", s"The partner master has no partner '$id'."))
    case e: (AccessDenied | DbError | DocumentError) => DocumentApi.toResponse(e)

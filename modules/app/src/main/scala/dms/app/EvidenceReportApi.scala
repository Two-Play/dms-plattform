package dms.app

import com.augustnagro.magnum.sql
import dms.archive.{DestructionError, LegalHoldError, ObjectRetentions}
import dms.archive.core.{DestructionService, LegalHoldService}
import dms.audit.*
import dms.audit.core.{
  AuditCheckpoints,
  AuditEvidenceLive,
  AuditTrailLive,
  CheckpointConfig,
  TsaConfig,
  TsaTrust
}
import dms.authz.{Action, Audience, Authorizer, Groups, Population, RoleAssignments}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.core.{
  AcknowledgementError,
  AcknowledgementService,
  AttachmentError,
  AttachmentUploads,
  DocumentDetails,
  DocumentError,
  ReviewService
}
import dms.documents.{DocumentStatus, DocumentVersion}
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.rendering.core.{PdfRenderer, PdfValidator, RenderingError, RenditionArchive}
import dms.storage.ObjectStore
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Base64, HexFormat, UUID}
import scala.util.Try

/** A generated evidence report: its registry entry, integrity result and where to download it. */
final case class EvidenceReportView(
    id: UUID,
    documentId: UUID,
    createdAt: Instant,
    createdBy: String,
    /** SHA-256 (lowercase hex) of the UTF-8 bytes of the JSON report. */
    sha256: String,
    format: String,
    /** Intact, Violated or Incomplete. */
    integrityStatus: String,
    findings: List[String],
    /** The JSON report: exactly the bytes the checksum is taken over. */
    jsonUrl: String,
    /** The same report as PDF/A-2b; every page names the report id and the checksum. */
    pdfUrl: String
) derives JsonCodec,
      Schema

/** An earlier report of a document (ADR 0077). Once its retention ended only the entry stays: `removedAt` is
  * set, status and download links are gone, and the checksum still matches the audit entry of its creation.
  */
final case class EvidenceReportEntryView(
    id: UUID,
    documentId: UUID,
    createdAt: Instant,
    createdBy: String,
    /** SHA-256 (lowercase hex) of the UTF-8 bytes of the JSON report. */
    sha256: String,
    format: String,
    /** Intact, Violated or Incomplete; empty once the report is removed. */
    integrityStatus: Option[String],
    /** When the content of the report was removed after its retention ended. */
    removedAt: Option[Instant],
    jsonUrl: Option[String],
    pdfUrl: Option[String]
) derives JsonCodec,
      Schema

/** One page of the reports of a document, the latest first; `nextOffset` is missing on the last page. */
final case class EvidenceReportPageView(
    items: List[EvidenceReportEntryView],
    total: Int,
    nextOffset: Option[Int]
) derives JsonCodec,
      Schema

/** Evidence reports of documents (US-12.1, DMS-57, ADR 0034).
  *
  * Internal audit and records managers (`GenerateEvidenceReport`) create a complete report of a document: the
  * version chain, review and approval steps with persons, reasons and timestamps (deputies included),
  * deviation requests, acknowledgement rates, periodic reviews, legal holds, the document's audit entries and
  * the integrity status (audit chain with anchors and checkpoints, rendition checksums against object
  * storage, approved content checksums). The report is registered with the SHA-256 of its canonical JSON
  * (migration V21) and its creation is audited; it can be downloaded as that JSON or as PDF/A. Earlier
  * reports are listed per document, for the same callers (ADR 0072, 0077).
  */
object EvidenceReportApi:
  type Env = DocumentApi.Env & AcknowledgementService & ReviewService & LegalHoldService &
    DestructionService & RoleAssignments & AuditTrail & AuditEvidence & EvidenceReportRegistry &
    RenditionArchive & PdfRenderer & PdfValidator & AttachmentUploads & TenantOrganizations & UserDirectory

  /** Audit action of a created report. */
  val CreatedAction: String = "document.evidence-report-created"

  /** Reports per page of the list, by default and at most. */
  val DefaultPage = 20
  val MaxPage     = 100

  private val documents  = "api" / "v1" / "documents"
  private val documentId = path[UUID]("id").description("Document id")

  /** `<report id>.pdf`; anything else does not match, so the JSON endpoint gets it. */
  private val pdfFile: Codec[String, UUID, CodecFormat.TextPlain] =
    Codec.string.mapDecode(s =>
      if !s.endsWith(".pdf") then DecodeResult.Mismatch("<report id>.pdf", s)
      else
        Try(UUID.fromString(s.stripSuffix(".pdf")))
          .fold(e => DecodeResult.Error(s, e), DecodeResult.Value(_))
    )(id => s"$id.pdf")

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "evidence-report")
      .out(statusCode(StatusCode.Created).and(jsonBody[EvidenceReportView]))
      .description(
        "Creates the evidence report of a document: version chain, review and approval steps with persons, reasons " +
          "and timestamps, acknowledgement rates, reviews, legal holds, audit entries and the integrity status. " +
          "Registered with the SHA-256 of its JSON form and audited. Requires the auditor or records manager role."
      )
      .tag("Evidence reports")
      .serverLogic(p => id => createReport(p, DocumentId(id)))

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(documents / documentId / "evidence-reports")
      .in(query[Option[Int]]("limit").description(s"Reports per page, 1 to $MaxPage; default $DefaultPage"))
      .in(query[Option[Int]]("offset").description("Reports to skip; default 0"))
      .out(jsonBody[EvidenceReportPageView])
      .description(
        "The evidence reports of a document, the latest first, also those whose content was removed after their " +
          "retention ended. For whoever may create a report of the document now (ADR 0072, 0077)."
      )
      .tag("Evidence reports")
      .serverLogic(p => (id, limit, offset) => page(p, DocumentId(id), limit, offset))

  val json: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(documents / documentId / "evidence-reports" / path[UUID]("reportId").description("Report id"))
      .out(header(Header.contentType(MediaType.ApplicationJson)))
      .out(header[String]("Content-Disposition"))
      .out(header[String]("ETag"))
      .out(header[String]("Repr-Digest").description("SHA-256 of the body (RFC 9530)"))
      .out(byteArrayBody)
      .description(
        "The JSON form of an evidence report, byte for byte as registered: its SHA-256 is the report checksum " +
          "(also sent as Repr-Digest and ETag). Requires the auditor or records manager role. 410 report_removed " +
          "once its retention ended."
      )
      .tag("Evidence reports")
      .serverLogic(p =>
        (id, reportId) =>
          load(p, DocumentId(id), reportId).map(r =>
            (
              s"""attachment; filename="evidence-report-${r.id}.json"""",
              s"\"${r.sha256}\"",
              digest(r.sha256),
              r.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            )
          )
      )

  val pdf: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(
        documents / documentId / "evidence-reports" /
          path[UUID]("reportFile")(using pdfFile).description("Report id followed by .pdf")
      )
      .out(header(Header.contentType(MediaType.ApplicationPdf)))
      .out(header[String]("Content-Disposition"))
      .out(header[String]("X-Evidence-Report-Sha256").description("SHA-256 of the JSON form"))
      .out(byteArrayBody)
      .description(
        "An evidence report as PDF/A-2b, printed from the registered JSON; every page names the report id and the " +
          "SHA-256 of the JSON form. Requires the auditor or records manager role. 410 report_removed once its " +
          "retention ended."
      )
      .tag("Evidence reports")
      .serverLogic(p =>
        (id, reportId) =>
          for
            stored  <- load(p, DocumentId(id), reportId)
            payload <- ZIO
              .fromEither(EvidenceReport.parse(stored.payload))
              .orElseFail(integrityError(s"evidence report ${stored.id} cannot be read"))
            rendered <- ZIO
              .serviceWithZIO[PdfRenderer](_.render(EvidenceReportLayout.job(payload, stored.sha256)))
              .mapError(RenditionApi.toResponse)
            report <- ZIO
              .serviceWithZIO[PdfValidator](_.validate(rendered.bytes, dms.rendering.ArchiveStandard.default))
              .mapError(RenditionApi.toResponse)
            _ <- ZIO.unless(report.compliant)(
              ZIO.fail(RenditionApi.toResponse(RenderingError.NotArchivable(report)))
            )
          yield (
            s"""attachment; filename="evidence-report-${stored.id}.pdf"""",
            stored.sha256,
            rendered.bytes.toArray
          )
      )

  // The PDF endpoint first: `<id>.pdf` must not be read as a report id.
  def endpoints: List[ZServerEndpoint[Env, Any]] = List(create, list, pdf, json)

  /** The [[AuditEvidence]] of the application: verification with the configured TSA trust anchors and the
    * write-once checkpoints in object storage (ADR 0017), as the background audit jobs use them.
    */
  def auditEvidence(config: AuditConfig): ZLayer[Db & ObjectStore, Throwable, AuditEvidence] =
    ZLayer.fromZIO(
      for
        db    <- ZIO.service[Db]
        store <- ZIO.service[ObjectStore]
        trust <- TsaTrust
          .fromConfig(TsaConfig(config.tsaUrl, trustedCertificates = config.tsaTrustedCertificates))
          .mapError(e => RuntimeException(e.message))
        trail = AuditTrailLive(db, trust)
      yield AuditEvidenceLive(db, trail, Some(AuditCheckpoints(db, trail, store, CheckpointConfig())))
    )

  private def createReport(
      p: Principal,
      id: DocumentId
  ): ZIO[Env, (StatusCode, ApiError), EvidenceReportView] =
    for
      acks        <- ZIO.service[AcknowledgementService]
      reviews     <- ZIO.service[ReviewService]
      holds       <- ZIO.service[LegalHoldService]
      destruction <- ZIO.service[DestructionService]
      assignments <- ZIO.service[RoleAssignments]
      groups      <- ZIO.serviceWith[Authorizer](_.groups)
      evidence    <- ZIO.service[AuditEvidence]
      facts       <- DocumentApi.inTransaction(p) { ctx =>
        for
          details <- ctx.service.get(id)
          _       <- authorize(ctx, p.tenantId, details)
          cases   <- ctx.workflow.cases(id)
          devs    <- ZIO.foreach(details.versions)(v => ctx.service.deviationRequests(v.id).map(v.id -> _))
          ackList <- ZIO.foreach(details.versions)(v =>
            acks.acknowledgementsOfVersion(v.id).mapBoth(acknowledgementError, v.id -> _)
          )
          audience <- audiences(p.tenantId, details, assignments, groups).mapError(DocumentError.Database(_))
          tasks    <- reviews.tasksOfDocument(id)
          held     <- holds.holdsOf(id).mapError(legalHoldError)
          removals <- ZIO.foreach(held)(h =>
            holds
              .documents(h.id)
              .mapBoth(
                legalHoldError,
                _.filter(_.documentId == id).lastOption.flatMap(_.removal).map(h.id.value -> _)
              )
          )
          entries    <- evidence.entriesOf(id.value).mapError(DocumentError.Database(_))
          records    <- destruction.recordsOf(id).mapError(destructionError)
          production <- renditionProduction(details.versions.map(_.id)).mapError(DocumentError.Database(_))
          locks      <- ObjectRetentions.ofDocument(id).mapError(DocumentError.Database(_))
        yield EvidenceFacts(
          details,
          cases,
          devs.toMap,
          ackList.toMap,
          audience,
          tasks,
          held,
          entries,
          details.versions.map(v => v.id -> WorkflowDocuments.contentSha256(v)).toMap,
          removals.flatten.toMap,
          records.map(r => r.item.versionId -> r).toMap,
          production,
          locks
            .map(l =>
              (l.objectKey, l.objectVersion) -> EvidenceObjectRetention(l.mode.toString, l.retainUntil)
            )
            .toMap
        )
      }
      // Outside the transaction: the chain verification opens its own, the renditions come from object storage.
      chain      <- evidence.integrity(p.tenantId, id.value).mapError(DocumentApi.toResponse)
      renditions <- checkRenditions(facts.details.versions)
      files      <- checkAttachments(facts.details.versions)
      now        <- Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))
      reportId = UUID.randomUUID()
      built    = EvidenceReport.build(
        reportId,
        now,
        p.userId.value,
        p.tenantId.value,
        facts,
        chain,
        renditions,
        files
      )
      // After the transaction: the directory is another system (ADR 0037). The names at this moment are recorded
      // in the report, so the same report always prints the same names (ADR 0077).
      names <- VersionViewApi.displayNames(p, EvidenceReport.personIds(built).map(UserId(_)))
      payload = EvidenceReport.named(built, names.map((id, name) => id.value -> name))
      text    = EvidenceReport.canonical(payload)
      stored  = StoredEvidenceReport(
        reportId,
        id.value,
        EvidenceReport.FormatNo,
        now,
        p.userId.value,
        text,
        EvidenceReport.sha256(text)
      )
      _ <- register(p, stored, payload)
    yield view(stored, payload)

  /** The report is about the document, so the role and its scope are decided on the governing versions. It
    * also shows every version, with votes, reasons and file names, so the caller must be cleared for each of
    * them, including those that have no say in the scope (DMS-155, ADR 0072). What was written about lowering
    * a version counts at the level it was written at (ADR 0083, decision 14): an older version would stop the
    * caller at that level only as long as it keeps its values. A version without values has no access groups
    * left to decide on, so it counts as it does for every other destroyed version.
    */
  private def authorize(ctx: DocumentApi.Context, tenant: TenantId, details: DocumentDetails) =
    for
      _ <- ZIO.when(details.versions.isEmpty)(
        ZIO.fail(DocumentError.NotFound(s"document ${details.document.id.value} has no version"))
      )
      _ <- ZIO.foreachDiscard(DocumentApi.governing(tenant, details))(
        ctx.check(Action.GenerateEvidenceReport, _)
      )
      _ <- ZIO.foreachDiscard(details.versions)(v =>
        ctx.checkCleared(Action.GenerateEvidenceReport, DocumentApi.resource(tenant, details, v))
      )
      _ <- ZIO.foreachDiscard(details.versions.filter(v => v.lowerings.nonEmpty && v.values.nonEmpty))(v =>
        ctx.checkCleared(Action.GenerateEvidenceReport, DocumentApi.loweringResource(tenant, details, v))
      )
    yield ()

  /** Who must acknowledge each published version that requires it (ADR 0027): everyone who may read it under
    * the policy with their current role assignments and access groups, decided by [[Audience]] as in the
    * compliance cockpit (ADR 0036). Versions that are no longer valid are evaluated as if they were valid.
    */
  private def audiences(
      tenant: TenantId,
      details: DocumentDetails,
      assignments: RoleAssignments,
      groups: Groups
  ): IO[DbError, Map[UUID, Set[String]]] =
    val versions = details.versions.filter(v => v.requiresAcknowledgement && v.publishedAt.isDefined)
    if versions.isEmpty then ZIO.succeed(Map.empty)
    else
      for
        now         <- Clock.instant
        active      <- assignments.activeAt(now)
        memberships <- groups.currentMemberships
        audience = Audience(Population(tenant, active, memberships), now)
      yield versions.map { v =>
        val resource =
          DocumentApi.resource(tenant, details, v).copy(status = Some(DocumentStatus.Published))
        v.id -> audience.readers(resource).members.map(_.value).toSet
      }.toMap

  /** Reads every rendition back from object storage and compares it with its stored SHA-256. */
  /** What produced the renditions of the versions (`dms.rendition_provenance`, DMS-93). */
  private def renditionProduction(
      versions: List[UUID]
  ): IO[DbError, Map[UUID, EvidenceRenditionProduction]] =
    Db.sql(
      sql"""select version_id, renderer, worker_image, worker_build, standard, validator
            from dms.rendition_provenance where version_id = any(${versions.toArray})"""
        .query[(UUID, String, Option[String], Option[String], String, String)]
        .run()
        .map((id, renderer, image, build, standard, validator) =>
          id -> EvidenceRenditionProduction(renderer, image, build, standard, validator)
        )
        .toMap
    )

  private def checkRenditions(
      versions: List[DocumentVersion]
  ): URIO[RenditionArchive, Map[UUID, RenditionCheck]] =
    ZIO.serviceWithZIO[RenditionArchive](archive =>
      ZIO
        // Destroyed versions (DMS-82) have no rendition any more; the report checks their destruction record.
        .foreach(
          versions.filterNot(_.status == DocumentStatus.Destroyed).flatMap(v => v.rendition.map(v.id -> _))
        ) { (versionId, rendition) =>
          archive
            .read(rendition)
            .as(RenditionCheck.Matches)
            .catchAll {
              case RenderingError.Integrity(m) => ZIO.succeed(RenditionCheck.Mismatch(m))
              case other                       => ZIO.succeed(RenditionCheck.Unverified(other.describe))
            }
            .map(versionId -> _)
        }
        .map(_.toMap)
    )

  /** Reads every attachment file back from object storage and compares it with its recorded SHA-256
    * (DMS-114); a file referenced by several versions is read once.
    */
  private def checkAttachments(
      versions: List[DocumentVersion]
  ): URIO[AttachmentUploads, Map[UUID, RenditionCheck]] =
    ZIO.serviceWithZIO[AttachmentUploads] { uploads =>
      // Attachments and the originals of file documents (DMS-115) share the stored files, keyed by file id.
      val files = versions.flatMap(_.attachments).map(a => a.fileId -> uploads.verify(a)) ++
        versions.flatMap(_.file).map(f => f.fileId -> uploads.verify(f))
      ZIO
        .foreach(files.distinctBy(_._1)) { (fileId, verification) =>
          verification
            .as(RenditionCheck.Matches)
            .catchAll {
              case AttachmentError.Integrity(m) => ZIO.succeed(RenditionCheck.Mismatch(m))
              case other                        => ZIO.succeed(RenditionCheck.Unverified(other.describe))
            }
            .map(fileId -> _)
        }
        .map(_.toMap)
    }

  private def register(
      p: Principal,
      stored: StoredEvidenceReport,
      payload: EvidenceReportPayload
  ): ZIO[Db & EvidenceReportRegistry & AuditTrail, (StatusCode, ApiError), Unit] =
    for
      db       <- ZIO.service[Db]
      registry <- ZIO.service[EvidenceReportRegistry]
      audit    <- ZIO.service[AuditTrail]
      details = Json.Obj(
        "reportId"        -> Json.Str(stored.id.toString),
        "sha256"          -> Json.Str(stored.sha256),
        "format"          -> Json.Str(payload.format),
        "integrityStatus" -> Json.Str(payload.integrity.status)
      )
      _ <- db
        .transact(p.tenantId)(
          registry.register(stored) *>
            audit.record(
              AuditEvent(
                Actor(p.userId.value, ActorKind.Person),
                CreatedAction,
                AuditObject("document", Some(stored.documentId)),
                Channel.Api,
                Jsonb(details.toJson)
              )
            )
        )
        .mapError(DocumentApi.toResponse)
    yield ()

  /** A registered report of the document, checked against its checksum; only for callers who may create one.
    * Its content goes when its retention ended (ADR 0077), which is not the same as a report that never was.
    */
  private def load(
      p: Principal,
      id: DocumentId,
      reportId: UUID
  ): ZIO[Env, (StatusCode, ApiError), StoredEvidenceReport] =
    for
      registry <- ZIO.service[EvidenceReportRegistry]
      found    <- DocumentApi.inTransaction(p) { ctx =>
        for
          details <- ctx.service.get(id)
          _       <- authorize(ctx, p.tenantId, details)
          stored  <- registry.find(reportId).mapError(DocumentError.Database(_))
          entry   <- registry.entry(reportId).mapError(DocumentError.Database(_))
          _       <- ZIO.unless(entry.exists(_.documentId == id.value))(
            ZIO.fail(DocumentError.NotFound(s"document ${id.value} has no evidence report $reportId"))
          )
        yield stored
      }
      stored <- ZIO
        .fromOption(found)
        .orElseFail(
          (
            StatusCode.Gone,
            ApiError(
              "report_removed",
              s"the content of evidence report $reportId was removed after its retention"
            )
          )
        )
      _ <- ZIO.unless(EvidenceReport.sha256(stored.payload) == stored.sha256)(
        ZIO.fail(integrityError(s"evidence report ${stored.id} does not match its registered checksum"))
      )
    yield stored

  /** The reports of a document for whoever may create one now: a stored report follows the same rule as a new
    * one (ADR 0072), and so does knowing that it exists.
    */
  private def page(
      p: Principal,
      id: DocumentId,
      limit: Option[Int],
      offset: Option[Int]
  ): ZIO[Env, (StatusCode, ApiError), EvidenceReportPageView] =
    val size = limit.getOrElse(DefaultPage).max(1).min(MaxPage)
    val from = offset.getOrElse(0).max(0)
    for
      registry <- ZIO.service[EvidenceReportRegistry]
      found    <- DocumentApi.inTransaction(p) { ctx =>
        for
          details <- ctx.service.get(id)
          _       <- authorize(ctx, p.tenantId, details)
          entries <- registry.entries(id.value, size, from).mapError(DocumentError.Database(_))
          total   <- registry.count(id.value).mapError(DocumentError.Database(_))
        yield (entries, total)
      }
      (entries, total) = found
      next             = from + entries.size
    yield EvidenceReportPageView(entries.map(entryView), total, Option.when(next < total)(next))

  private def entryView(e: EvidenceReportEntry): EvidenceReportEntryView =
    val base = s"/api/v1/documents/${e.documentId}/evidence-reports/${e.id}"
    val kept = e.removedAt.isEmpty
    EvidenceReportEntryView(
      e.id,
      e.documentId,
      e.createdAt,
      e.createdBy,
      e.sha256,
      s"dms.evidence-report/${e.format}",
      e.integrityStatus,
      e.removedAt,
      Option.when(kept)(base),
      Option.when(kept)(s"$base.pdf")
    )

  private def view(stored: StoredEvidenceReport, payload: EvidenceReportPayload): EvidenceReportView =
    val base = s"/api/v1/documents/${stored.documentId}/evidence-reports/${stored.id}"
    EvidenceReportView(
      stored.id,
      stored.documentId,
      stored.createdAt,
      stored.createdBy,
      stored.sha256,
      payload.format,
      payload.integrity.status,
      payload.integrity.findings,
      base,
      s"$base.pdf"
    )

  private def digest(sha256: String): String =
    s"sha-256=:${Base64.getEncoder.encodeToString(HexFormat.of().parseHex(sha256))}:"

  private def integrityError(message: String): (StatusCode, ApiError) =
    (StatusCode.InternalServerError, ApiError("report_integrity", message))

  private def acknowledgementError(e: AcknowledgementError): DocumentError = e match
    case AcknowledgementError.Database(db) => DocumentError.Database(db)
    case other                             => DocumentError.Conflict(other.describe)

  private def destructionError(e: DestructionError): DocumentError = e match
    case DestructionError.Database(db) => DocumentError.Database(db)
    case other                         => DocumentError.Conflict(other.describe)

  private def legalHoldError(e: LegalHoldError): DocumentError = e match
    case LegalHoldError.Database(db) => DocumentError.Database(db)
    case other                       => DocumentError.Conflict(other.describe)

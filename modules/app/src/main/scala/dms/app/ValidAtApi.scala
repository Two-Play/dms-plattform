package dms.app

import dms.authz.Action
import dms.documents.Validity
import dms.documents.core.{DocumentError, ValidVersion}
import dms.identity.Principal
import dms.workflow.Approval
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/** A version that was in force at the requested moment (US-09.1, DMS-53). */
final case class ValidVersionView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    classPath: List[String],
    /** Version number, e.g. `2.0`. */
    versionLabel: String,
    /** DocumentStatus of the version now, e.g. Published, or Superseded if it was replaced since. */
    status: String,
    /** Validity scope; empty: unrestricted. One version per document and scope. */
    scope: String,
    /** The valid-from date of the version (its `validFrom` field as approved). */
    validFrom: Option[LocalDate],
    /** When the version became valid: 00:00 of its valid-from date in the tenant's time zone, or its
      * publication if that was later.
      */
    validSince: Instant,
    /** When its successor became valid; empty while the version is still valid. */
    validUntil: Option[Instant],
    /** Who approved the version (a deputy, see `actingFor`). */
    approvedBy: Option[String],
    /** The approver the deputy `approvedBy` stood in for (DMS-49). */
    actingFor: Option[String],
    approvedAt: Option[Instant]
) derives JsonCodec,
      Schema

final case class ValidAtView(
    /** The moment asked about: the last moment of `date` in the tenant's time zone, or `at` as given. */
    at: Instant,
    date: Option[LocalDate],
    /** The tenant's time zone, in which dates are read. */
    timeZone: String,
    /** The class filter, if any. */
    documentClass: Option[String],
    /** Per document and validity scope the version in force, by title. */
    items: List[ValidVersionView]
) derives JsonCodec,
      Schema

/** The validity history for auditors (business concept 7, US-09.1, DMS-53): which version of each document
  * was in force on a date, as a list and as a CSV export.
  *
  * The history is read from PostgreSQL, the authoritative store of validity (ADR 0021, 0026); the search
  * index is not involved. Each version is shown only if the caller may read it now with `ReadDocument` in its
  * current status: readers of the class see versions that are valid or were superseded (like the version
  * history of a document), archived versions only owners, records managers and auditors, and confidentiality
  * applies as everywhere. Versions the caller may not read are left out without a denial.
  */
object ValidAtApi:
  type Env = DocumentApi.Env

  private val validAt = "api" / "v1" / "documents" / "valid-at"

  private val filter =
    query[Option[LocalDate]]("date")
      .description(
        "Valid on this date (YYYY-MM-DD), read in the tenant's time zone: the version in force at the end of " +
          "the day. Either date or at is required."
      )
      .and(
        query[Option[Instant]]("at").description("Valid at this exact moment (ISO 8601 instant)")
      )
      .and(
        query[Option[String]]("class").description("Only documents of this class key and its subclasses")
      )

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(validAt)
      .in(filter)
      .out(jsonBody[ValidAtView])
      .description(
        "For each document and validity scope the version that was in force on a date (or at a moment): from " +
          "00:00 of its valid-from date in the tenant's time zone (or its publication, if later) until its " +
          "successor became valid. Only versions the caller may read; 422 unless exactly one of date and at is " +
          "given."
      )
      .tag("Documents")
      .serverLogic(p => (date, at, cls) => report(p, date, at, cls))

  val exportCsv: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(validAt / "export.csv")
      .in(filter)
      .out(header(Header.contentType(MediaType.TextCsv.charset(StandardCharsets.UTF_8))))
      .out(header[String]("Content-Disposition"))
      .out(byteArrayBody)
      .description(
        "The list of `GET /api/v1/documents/valid-at` as CSV for spreadsheets: UTF-8 with byte order mark, " +
          "semicolon-separated, CRLF line ends, times in the tenant's time zone. Columns: document id, title, " +
          "class, scope, version, status, valid from, valid until, approver, on behalf of, approved at."
      )
      .tag("Documents")
      .serverLogic(p =>
        (date, at, cls) =>
          report(p, date, at, cls).map(r =>
            val name = r.date.fold(r.at.toString.replace(":", "-"))(_.toString)
            (s"""attachment; filename="valid-at-$name.csv"""", Csv.of(r).getBytes(StandardCharsets.UTF_8))
          )
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(list, exportCsv)

  private def report(
      p: Principal,
      date: Option[LocalDate],
      at: Option[Instant],
      cls: Option[String]
  ): ZIO[Env, (StatusCode, ApiError), ValidAtView] =
    DocumentApi.inTransaction(p) { ctx =>
      for
        zone   <- ctx.service.tenantZone
        moment <- (date, at) match
          case (Some(d), None) => ZIO.succeed(Validity.endOfDay(d, zone))
          case (None, Some(a)) => ZIO.succeed(a)
          case _ => ZIO.fail(DocumentError.Invalid(List("give either date or at, not both and not neither")))
        classKey = cls.map(_.trim).filter(_.nonEmpty)
        versions  <- ctx.service.validAt(moment, classKey)
        readable  <- ZIO.filter(versions)(v => ctx.allowed(Action.ReadDocument, resource(p, v)))
        approvals <- ctx.workflow.approvals(readable.map(_.versionId))
      yield ValidAtView(
        moment,
        date,
        zone.getId,
        classKey,
        readable.map(v => view(v, approvals.get(v.versionId)))
      )
    }

  private def resource(p: Principal, v: ValidVersion) =
    DocumentApi.versionResource(
      p.tenantId,
      v.classPath,
      v.document.ownerId,
      v.status,
      v.createdBy,
      v.metadata,
      v.releasedBy
    )

  /** Approver and time from the approval record of the workflow, or from the version for versions approved
    * without one.
    */
  private def view(v: ValidVersion, approval: Option[Approval]) =
    ValidVersionView(
      v.document.id.value,
      v.versionId,
      v.document.title,
      v.classPath,
      v.label,
      v.status.toString,
      v.scope,
      v.validFrom,
      v.period.from,
      v.period.until,
      approval.map(_.approvedBy.value).orElse(v.releasedBy.map(_.value)),
      approval.flatMap(_.actingFor.map(_.value)),
      approval.map(_.approvedAt).orElse(v.releasedAt)
    )

  /** CSV for spreadsheet programs: Excel opens UTF-8 only with a byte order mark, and with German regional
    * settings (the tenants' default) it splits columns at semicolons. Text cells that a spreadsheet would
    * take for a formula get a leading apostrophe (CSV injection).
    */
  private[app] object Csv:
    private val Bom = "﻿"

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    def of(r: ValidAtView): String =
      val zone   = ZoneId.of(r.timeZone)
      val header = List(
        "Document id",
        "Title",
        "Class",
        "Scope",
        "Version",
        "Status",
        s"Valid from (${r.timeZone})",
        s"Valid until (${r.timeZone})",
        "Approver",
        "On behalf of",
        s"Approved at (${r.timeZone})"
      )
      def at(i: Instant) = time.format(i.atZone(zone))
      val rows           = r.items.map(v =>
        List(
          v.documentId.toString,
          text(v.title),
          text(v.classPath.mkString("/")),
          text(v.scope),
          v.versionLabel,
          v.status,
          at(v.validSince),
          v.validUntil.fold("")(at),
          v.approvedBy.fold("")(text),
          v.actingFor.fold("")(text),
          v.approvedAt.fold("")(at)
        )
      )
      Bom + (header :: rows).map(_.map(cell).mkString(";")).mkString("", "\r\n", "\r\n")

    /** Neutralizes values a spreadsheet would evaluate as a formula. */
    def text(value: String): String =
      if value.headOption.exists(c => "=+-@\t\r".contains(c)) then s"'$value" else value

    /** Quotes a cell that contains a separator, quote or line break (RFC 4180). */
    def cell(value: String): String =
      if value.exists(c => c == ';' || c == '"' || c == '\n' || c == '\r') then
        "\"" + value.replace("\"", "\"\"") + "\""
      else value

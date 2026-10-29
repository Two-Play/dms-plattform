package dms.app

import dms.documents.*
import dms.documents.core.{DocumentDetails, DocumentError}
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.MetadataValue
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.DocumentId
import dms.templates.{
  Block,
  BlockDiff,
  ContentDiff,
  Inline,
  PathStep,
  Section,
  SectionDiff,
  SectionId,
  StructureChange,
  TemplateContent
}
import dms.workflow.Approval
import sttp.model.StatusCode
import dms.templates.core.DeviationJson.given
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

import DeviationSchemas.given
import MetadataSchemas.given

/** One of the two compared versions. */
final case class ComparedVersionView(
    versionId: UUID,
    /** `1.1-draft.3` while open, `1.1` from approval on. */
    version: String,
    /** DocumentStatus now, e.g. Published or Superseded. */
    status: String,
    /** Major or Minor. */
    changeKind: String,
    createdBy: String,
    createdAt: Instant,
    releasedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** What the author of a version wrote about its changes when starting it. */
final case class ChangeSummaryView(
    versionId: UUID,
    version: String,
    /** Major or Minor. */
    changeKind: String,
    /** The reason given for the version; empty if the author gave none. */
    summary: Option[String],
    createdBy: String,
    createdAt: Instant
) derives JsonCodec,
      Schema

/** A run of words: `Equal` (in both versions), `Inserted` (only in the later) or `Deleted` (only in the
  * earlier). Text, not HTML: words separated by single spaces, placeholders as `{{path}}`, line breaks as
  * `\n`.
  */
final case class TextSpanView(kind: String, text: String) derives JsonCodec, Schema

/** A change that is not a change of words: `HeadingLevel`, `Formatting`, `Condition`, `Repeat`, `Clause` or
  * `HeaderRows`, with the earlier and later value where there is one.
  */
final case class StructureChangeView(kind: String, from: Option[String], to: Option[String])
    derives JsonCodec,
      Schema

final case class BlockDiffView(
    /** Position in the later version's section (for deleted blocks: where they were removed). */
    path: List[PathStep],
    /** Readable position, e.g. `block 2 › item 1 › block 0`. */
    location: String,
    /** Heading, Paragraph, ClauseRef, Conditional, Repeat, PageBreak (or Table for a header row change). */
    kind: String,
    /** Heading level. */
    level: Option[Int],
    /** Unchanged, Inserted, Deleted or Changed. */
    status: String,
    spans: List[TextSpanView],
    changes: List[StructureChangeView]
) derives JsonCodec,
      Schema

final case class SectionDiffView(
    id: String,
    titleBefore: Option[String],
    titleAfter: Option[String],
    /** Unchanged, Inserted, Deleted or Changed. */
    status: String,
    /** The section is at another position among the sections. */
    moved: Boolean,
    lockedBefore: Option[Boolean],
    lockedAfter: Option[Boolean],
    blocks: List[BlockDiffView]
) derives JsonCodec,
      Schema

final case class TextComparisonView(
    changed: Boolean,
    insertedWords: Int,
    deletedWords: Int,
    /** All sections of both versions in the order of the later version, unchanged ones included. */
    sections: List[SectionDiffView]
) derives JsonCodec,
      Schema

/** A value that differs: the value, its origin (`Manual`, `System` with `source`, `Computed`) or both. */
final case class ValueChangeView(
    key: String,
    label: String,
    before: Option[MetadataValue],
    after: Option[MetadataValue],
    originBefore: Option[String],
    sourceBefore: Option[String],
    originAfter: Option[String],
    sourceAfter: Option[String]
) derives JsonCodec,
      Schema

/** The original of a file version (DMS-115): file name, size and SHA-256. */
final case class OriginalFileView(name: String, size: Long, sha256: String) derives JsonCodec, Schema

/** The originals of two file versions (DMS-121). */
final case class OriginalComparisonView(
    /** The SHA-256 (or the presence) of the original differs. */
    changed: Boolean,
    before: Option[OriginalFileView],
    after: Option[OriginalFileView],
    /** True if `text` compares the text of the originals: Word documents (DOCX) with their fields filled.
      * False for other formats (PDF, spreadsheets, ...), whose text is not compared; only the file is.
      */
    textCompared: Boolean
) derives JsonCodec,
      Schema

final case class VersionComparisonView(
    documentId: UUID,
    title: String,
    from: ComparedVersionView,
    to: ComparedVersionView,
    /** The change summaries of the versions after the earlier compared version up to the later one, oldest
      * first; versions the caller may not read and withdrawn ones in between are left out.
      */
    summaries: List[ChangeSummaryView],
    text: TextComparisonView,
    /** Changed field values of the document class, with their origin. */
    fields: List[ValueChangeView],
    /** Changed core and system metadata and version properties (status, approval, rendition checksum). */
    metadata: List[ValueChangeView],
    /** File documents only (DMS-121): the originals of both versions. `text` then compares the paragraphs of
      * the filled Word documents, and `fields` includes the manual Word field values (keys `word:<key>`).
      */
    original: Option[OriginalComparisonView] = None
) derives JsonCodec,
      Schema

/** Comparison of two versions of a document (US-05.1, DMS-54, ADR 0033): the text, the field values and the
  * metadata separately, with the authors' change summaries. Both versions need `ReadDocument` in their
  * current status, exactly as reading them one by one; one unreadable version denies the comparison (403).
  *
  * File documents (DMS-121, ADR 0044): the text is that of the Word documents with their fields filled (as
  * the filled DOCX download gives them), one paragraph per block; the manual Word field values are compared
  * with the field values and the original's name and SHA-256 with the metadata. Originals in other formats
  * are compared as files only.
  */
object VersionCompareApi:
  type Env = DocumentApi.Env & FileVersions & TenantOrganizations & UserDirectory

  val compare: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "compare")
      .in(query[UUID]("from").description("Id of the version compared from, usually the earlier one"))
      .in(query[UUID]("to").description("Id of the version compared to, usually the later one"))
      .out(jsonBody[VersionComparisonView])
      .description(
        "Compares two versions of a document: word-level text changes per section and block (inserted, deleted, " +
          "structural changes), changed field values with their origin and changed metadata, with the change " +
          "summaries of the versions in between. File documents: the paragraphs of the Word documents with their " +
          "fields filled, the manual Word field values and the originals (`original`); originals in other formats " +
          "are compared by name and SHA-256 only. Both versions must be readable by the caller (403 otherwise); " +
          "404 if a version does not belong to the document; 503 if the original of a file version cannot be read."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, fromId, toId) =>
          for
            found <- DocumentApi.inTransaction(p) { ctx =>
              for
                details <- ctx.service.get(DocumentId(id))
                from    <- version(details, fromId)
                to      <- version(details, toId)
                _       <- ctx.checkRead(details, from)
                _       <- ctx.checkRead(details, to)
                visible <- ctx.visible(details)
                cases   <-
                  if details.document.kind == DocumentKind.File then ctx.workflow.cases(details.document.id)
                  else ZIO.succeed(Nil)
              yield (details, from, to, visible, cases.flatMap(_.approval))
            }
            (details, from, to, visible, approvals) = found
            file                                    = details.document.kind == DocumentKind.File
            textCompared                            = file && List(from, to).forall(_.file.forall(_.isWord))
            contents <-
              if textCompared then
                (wordContent(p, details, from, approvals) <*> wordContent(p, details, to, approvals)).asSome
              else if file then ZIO.some((TemplateContent(Nil), TemplateContent(Nil)))
              else ZIO.none
            comparison = VersionComparison.compare(details.profile, from, to, visible, contents)
          yield view(details, comparison).copy(original =
            Option.when(file)(
              OriginalComparisonView(
                from.file.map(_.sha256) != to.file.map(_.sha256),
                from.file.map(originalView),
                to.file.map(originalView),
                textCompared
              )
            )
          )
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(compare)

  /** Section id of the text of a file version in the comparison. */
  val OriginalSection: String = "original"

  /** The paragraphs of a file version's Word document, filled as the filled DOCX download gives it, as one
    * section of paragraphs and headings; empty without file.
    */
  private def wordContent(
      p: Principal,
      details: DocumentDetails,
      v: DocumentVersion,
      approvals: List[Approval]
  ): ZIO[FileVersions & TenantOrganizations & UserDirectory, (StatusCode, ApiError), TemplateContent] =
    if v.file.isEmpty then ZIO.succeed(TemplateContent(Nil))
    else
      val approval = FileVersions.approvalOf(v, approvals.find(_.versionId == v.id))
      for
        names      <- VersionViewApi.displayNames(p, WordFilling.people(details, v, approval))
        filled     <- FileVersions.filled(details, v, names, approval).mapError(VersionViewApi.toResponse)
        paragraphs <- ZIO
          .fromEither(WordDocx.paragraphs(filled.toArray))
          .mapError(m =>
            (StatusCode.UnprocessableEntity, ApiError("invalid_docx", s"version ${v.label}: $m"))
          )
      yield content(paragraphs)

  /** Paragraphs as the blocks of one section; line breaks inside a paragraph stay line breaks. */
  private[app] def content(paragraphs: List[WordParagraph]): TemplateContent =
    def inlines(text: String): List[Inline] =
      text.split('\n').toList.flatMap(line => List(Inline.LineBreak, Inline.Text(line))).drop(1)
    val blocks = paragraphs.map(p =>
      p.headingLevel.fold(Block.Paragraph(inlines(p.text)))(level => Block.Heading(level, inlines(p.text)))
    )
    TemplateContent(
      Option.when(blocks.nonEmpty)(Section(SectionId(OriginalSection), None, false, blocks)).toList
    )

  private def originalView(f: VersionFile): OriginalFileView = OriginalFileView(f.name, f.size, f.sha256)

  private def version(details: DocumentDetails, versionId: UUID): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(details.versions.find(_.id == versionId))
      .orElseFail(DocumentError.NotFound(s"document ${details.document.id.value} has no version $versionId"))

  private def view(details: DocumentDetails, c: VersionComparison): VersionComparisonView =
    VersionComparisonView(
      details.document.id.value,
      details.document.title,
      versionView(c.from),
      versionView(c.to),
      c.summaries.map(s =>
        ChangeSummaryView(
          s.versionId,
          s.label,
          s.changeKind.toString,
          s.summary,
          s.createdBy.value,
          s.createdAt
        )
      ),
      textView(c.text),
      c.fields.map(valueView),
      c.metadata.map(valueView)
    )

  private def versionView(v: DocumentVersion): ComparedVersionView =
    ComparedVersionView(
      v.id,
      v.label,
      v.status.toString,
      v.changeKind.toString,
      v.createdBy.value,
      v.createdAt,
      v.releasedAt
    )

  private def textView(d: ContentDiff): TextComparisonView =
    TextComparisonView(d.changed, d.insertedWords, d.deletedWords, d.sections.map(sectionView))

  private def sectionView(s: SectionDiff): SectionDiffView =
    SectionDiffView(
      s.id.value,
      s.titleBefore,
      s.titleAfter,
      s.status.toString,
      s.moved,
      s.lockedBefore,
      s.lockedAfter,
      s.blocks.map(blockView)
    )

  private def blockView(b: BlockDiff): BlockDiffView =
    BlockDiffView(
      b.path,
      b.path.map(_.show).mkString(" › "),
      b.kind,
      b.level,
      b.status.toString,
      b.spans.map(s => TextSpanView(s.kind.toString, s.text)),
      b.changes.map(structureView)
    )

  private def structureView(c: StructureChange): StructureChangeView =
    def change(from: String, to: String) = StructureChangeView(c.productPrefix, Some(from), Some(to))
    c match
      case StructureChange.HeadingLevel(from, to) => change(from.toString, to.toString)
      case StructureChange.Formatting             => StructureChangeView(c.productPrefix, None, None)
      case StructureChange.Condition(from, to)    => change(from, to)
      case StructureChange.Repeat(from, to)       => change(from, to)
      case StructureChange.Clause(from, to)       => change(from, to)
      case StructureChange.HeaderRows(from, to)   => change(from.toString, to.toString)

  private def origin(o: ValueOrigin): (String, Option[String]) = o match
    case ValueOrigin.Manual         => ("Manual", None)
    case ValueOrigin.System(source) => ("System", Some(source))
    case ValueOrigin.Computed       => ("Computed", None)

  private def valueView(v: ValueChange): ValueChangeView =
    val before = v.originBefore.map(origin)
    val after  = v.originAfter.map(origin)
    ValueChangeView(
      v.key,
      v.label,
      v.before,
      v.after,
      before.map(_._1),
      before.flatMap(_._2),
      after.map(_._1),
      after.flatMap(_._2)
    )
end VersionCompareApi

package dms.documents

import dms.documents.metadata.{DocumentClassId, MetadataValue}
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The rules behind the reports for records management, and what a move of a draft reaches (DMS-162, ADR
  * 0084).
  */
object DocumentRecordSpec extends ZIOSpecDefault:

  private val template = TemplateId(UUID.randomUUID())
  private val now      = Instant.parse("2026-11-06T10:00:00Z")
  private val anna     = UserId("anna")
  private val ben      = UserId("ben")

  private val document = Document(
    DocumentId.random(),
    DocumentClassId(UUID.randomUUID()),
    1,
    "Taken over",
    Some(ben),
    anna,
    template,
    1,
    now
  )

  private def entries(values: (String, MetadataValue)*) =
    values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual)).toMap

  private def named(owner: UserId = anna, title: String = "Policy") = entries(
    "owner" -> MetadataValue.PersonRef(owner.value),
    "title" -> MetadataValue.Text(title)
  )

  private def level(code: String) = entries("confidentiality" -> MetadataValue.Selection(code))
  private def unit(name: String)  = entries("organizationalUnit" -> MetadataValue.Text(name))

  private def version(
      major: Int,
      status: DocumentStatus,
      values: Map[String, FieldEntry] = Map.empty,
      publishedDaysAgo: Option[Long] = None,
      released: Boolean = false
  ) =
    DocumentVersion(
      UUID.randomUUID(),
      document.id,
      VersionNo(major, 0),
      1,
      status,
      ChangeKind.Major,
      None,
      "",
      1,
      template,
      1,
      TemplateContent(Nil),
      values,
      None,
      None,
      anna,
      now,
      now,
      None,
      Option.when(released || publishedDaysAgo.isDefined)(now),
      publishedAt = publishedDaysAgo.map(d => now.minusSeconds(d * 86400))
    )

  private def lowering(status: LoweringStatus) =
    ConfidentialityLowering(
      UUID.randomUUID(),
      UUID.randomUUID(),
      "Confidential",
      "Internal",
      "No longer sensitive",
      anna,
      now,
      status
    )

  def spec = suite("Records of a document (ADR 0084)")(
    suite("The last version in force")(
      test("of several valid versions the one published last, whatever its number") {
        val older = version(3, DocumentStatus.Published, publishedDaysAgo = Some(9))
        val newer = version(2, DocumentStatus.ReviewDue, publishedDaysAgo = Some(1))
        assertTrue(Versioning.lastInForce(List(newer, older)).contains(newer))
      },
      test("a valid version goes before one published later that ended") {
        val valid = version(1, DocumentStatus.Published, publishedDaysAgo = Some(9))
        val ended = version(2, DocumentStatus.Withdrawn, publishedDaysAgo = Some(1))
        assertTrue(Versioning.lastInForce(List(valid, ended)).contains(valid))
      },
      test("without a valid version the last that was in force, never a destroyed one") {
        val ended     = version(1, DocumentStatus.Archived, publishedDaysAgo = Some(9))
        val destroyed = version(2, DocumentStatus.Destroyed, publishedDaysAgo = Some(1))
        assertTrue(Versioning.lastInForce(List(ended, destroyed)).contains(ended))
      },
      test("nothing that never took effect") {
        assertTrue(
          Versioning
            .lastInForce(
              List(version(1, DocumentStatus.Approved, released = true), version(1, DocumentStatus.Draft))
            )
            .isEmpty
        )
      }
    ),
    suite("Differences")(
      test("names what differs, and only that") {
        val valid = version(1, DocumentStatus.Published, named(), Some(1))
        val owner = DocumentRecord.difference(document.copy(title = "Policy"), List(valid))
        val title = DocumentRecord.difference(document.copy(ownerId = Some(anna)), List(valid))
        assertTrue(
          owner.exists(d => d.owner.contains(anna) && d.title.isEmpty),
          title.exists(d => d.owner.isEmpty && d.title.contains("Policy"))
        )
      },
      test("a draft's values do not count, the valid version's do") {
        val valid = version(1, DocumentStatus.Published, named(ben, "Taken over"), Some(1))
        val draft = version(2, DocumentStatus.Draft, named())
        assertTrue(DocumentRecord.difference(document, List(valid, draft)).isEmpty)
      },
      test("a value the version does not have says nothing, as at publication") {
        val valid = version(1, DocumentStatus.Published, Map.empty, Some(1))
        assertTrue(DocumentRecord.difference(document, List(valid)).isEmpty)
      },
      test("spaces around the version's title are no difference") {
        val valid = version(1, DocumentStatus.Published, named(ben, " Taken over "), Some(1))
        assertTrue(DocumentRecord.difference(document, List(valid)).isEmpty)
      },
      test("a document without a version in force has none") {
        assertTrue(
          DocumentRecord.difference(document, List(version(1, DocumentStatus.Draft, named()))).isEmpty
        )
      }
    ),
    suite("Waiting for the workflow")(
      test("review, approval and an approved version hold a correction back, a draft does not") {
        def waits(s: DocumentStatus) = DocumentRecord.inWorkflow(List(version(2, s))).isDefined
        assertTrue(
          waits(DocumentStatus.InReview),
          waits(DocumentStatus.InApproval),
          waits(DocumentStatus.Approved),
          !waits(DocumentStatus.Draft),
          !waits(DocumentStatus.Published)
        )
      }
    ),
    suite("Open versions below their basis")(
      test("a level below that of the basis counts, also a missing one") {
        val valid   = version(1, DocumentStatus.Published, level("Confidential"), Some(1))
        val lower   = version(2, DocumentStatus.Draft, level("Internal"))
        val missing = version(2, DocumentStatus.InReview)
        assertTrue(
          DocumentRecord.belowBasis(List(valid, lower)).exists(_.basis == valid),
          DocumentRecord.belowBasis(List(valid, missing)).exists(_.version == missing)
        )
      },
      test("the same level, a higher one and a first version do not") {
        val valid = version(1, DocumentStatus.Published, level("Confidential"), Some(1))
        assertTrue(
          DocumentRecord
            .belowBasis(List(valid, version(2, DocumentStatus.Draft, level("Confidential"))))
            .isEmpty,
          DocumentRecord
            .belowBasis(List(valid, version(2, DocumentStatus.Draft, level("StrictlyConfidential"))))
            .isEmpty,
          DocumentRecord.belowBasis(List(version(1, DocumentStatus.Draft, level("Public")))).isEmpty
        )
      },
      test("an approved lowering keeps the version at its old level for access") {
        val valid = version(1, DocumentStatus.Published, level("Confidential"), Some(1))
        val open  = version(2, DocumentStatus.InApproval, level("Internal"))
          .copy(lowerings = List(lowering(LoweringStatus.Approved)))
        assertTrue(DocumentRecord.belowBasis(List(valid, open)).isEmpty)
      },
      test("a basis lowered but never published stands at its old level") {
        val stopped = version(1, DocumentStatus.Withdrawn, level("Internal"), released = true).copy(
          withdrawal = Some(VersionWithdrawal(now, anna, "Stopped")),
          lowerings = List(lowering(LoweringStatus.Approved))
        )
        val open = version(2, DocumentStatus.Draft, level("Internal"))
        assertTrue(DocumentRecord.belowBasis(List(stopped, open)).exists(_.basis == stopped))
      }
    ),
    suite("What a move reaches")(
      test("a unit neither the draft nor its basis is in") {
        val valid = version(1, DocumentStatus.Published, unit("Finance"), Some(1))
        val draft = version(2, DocumentStatus.Draft, unit("Finance"))
        val after = Map("organizationalUnit" -> MetadataValue.Text("Sales"))
        assertTrue(Versioning.reachedUnit(draft, after, List(valid, draft)).contains("Sales"))
      },
      test("a selection counts like a text") {
        val valid = version(1, DocumentStatus.Published, unit("Finance"), Some(1))
        val draft = version(2, DocumentStatus.Draft, unit("Finance"))
        val after = Map("organizationalUnit" -> MetadataValue.Selection("Sales"))
        assertTrue(Versioning.reachedUnit(draft, after, List(valid, draft)).contains("Sales"))
      },
      test("not the way back to the basis, nor staying where the owner moved it") {
        val valid = version(1, DocumentStatus.Published, unit("Finance"), Some(1))
        val moved = version(2, DocumentStatus.Draft, unit("Sales"))
        assertTrue(
          Versioning
            .reachedUnit(
              moved,
              Map("organizationalUnit" -> MetadataValue.Text("Finance")),
              List(valid, moved)
            )
            .isEmpty,
          Versioning
            .reachedUnit(moved, Map("organizationalUnit" -> MetadataValue.Text("Sales")), List(valid, moved))
            .isEmpty
        )
      },
      test("not a first version, and not a version without a unit") {
        val first = version(1, DocumentStatus.Draft, unit("Finance"))
        val valid = version(1, DocumentStatus.Published, unit("Finance"), Some(1))
        val draft = version(2, DocumentStatus.Draft, unit("Finance"))
        assertTrue(
          Versioning
            .reachedUnit(first, Map("organizationalUnit" -> MetadataValue.Text("Sales")), List(first))
            .isEmpty,
          Versioning.reachedUnit(draft, Map.empty, List(valid, draft)).isEmpty
        )
      }
    )
  )

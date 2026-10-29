package dms.documents

import dms.documents.CurrentVersion.Resolution
import dms.documents.DocumentStatus.*
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

object CurrentVersionSpec extends ZIOSpecDefault:

  private val document = DocumentId(UUID.randomUUID())

  private def version(major: Int, status: DocumentStatus, scope: String = "", published: Boolean = true) =
    DocumentVersion(
      UUID.randomUUID(),
      document,
      VersionNo.parse(s"$major.0").get,
      1,
      status,
      ChangeKind.Major,
      None,
      scope,
      1,
      TemplateId(UUID.randomUUID()),
      1,
      TemplateContent(Nil),
      Map.empty,
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      None,
      None,
      publishedAt = Option.when(published)(Instant.EPOCH)
    )

  def spec = suite("Current version of a stable address")(
    test("the valid version is current; superseded and open versions are not") {
      val old     = version(1, Superseded)
      val current = version(2, Published)
      val draft   = version(3, Draft, published = false)
      assertTrue(
        CurrentVersion.resolve(List(old, current, draft), None) == Resolution.Found(current),
        CurrentVersion.resolve(List(old, version(2, ReviewDue)), None).isInstanceOf[Resolution.Found]
      )
    },
    test("a scope picks the version of its scope key or the only one that contains it") {
      val de     = version(1, Published, "DE")
      val atDe   = version(2, Published, "AT|DE")
      val ch     = version(3, Published, "CH")
      val global = version(4, Published)
      assertTrue(
        CurrentVersion.resolve(List(de, atDe, ch), Some("DE")) == Resolution.Found(de),
        CurrentVersion.resolve(List(de, atDe, ch), Some(" AT ")) == Resolution.Found(atDe),
        CurrentVersion.resolve(List(de, atDe, ch), Some("FR")) == Resolution.NotFound,
        CurrentVersion.resolve(List(de, atDe, ch), None) == Resolution.Ambiguous(List("AT|DE", "CH", "DE")),
        CurrentVersion.resolve(List(de, global), None) == Resolution.Found(global),
        CurrentVersion.resolve(List(atDe), None) == Resolution.Found(atDe)
      )
    },
    test("a version withdrawn without successor is reported as withdrawn; one never published is not") {
      val withdrawn = version(1, Withdrawn)
      val archived  = version(2, Archived)
      val draft     = version(3, Withdrawn, published = false)
      assertTrue(
        CurrentVersion.resolve(List(withdrawn), None) == Resolution.Withdrawn(withdrawn),
        CurrentVersion.resolve(List(withdrawn, archived), None) == Resolution.Withdrawn(archived),
        CurrentVersion.resolve(List(draft), None) == Resolution.NotFound,
        CurrentVersion.resolve(List(withdrawn, version(2, Published)), None).isInstanceOf[Resolution.Found],
        CurrentVersion.resolve(Nil, Some("DE")) == Resolution.NotFound
      )
    }
  )

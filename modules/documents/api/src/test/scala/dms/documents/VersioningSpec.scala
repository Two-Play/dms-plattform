package dms.documents

import dms.documents.ChangeKind.*
import dms.documents.DocumentStatus.*
import dms.documents.metadata.MetadataValue
import dms.kernel.VersionNo
import zio.test.*

object VersioningSpec extends ZIOSpecDefault:

  private def scope(values: String*) = Map(
    "scope" -> MetadataValue.Many(values.toList.map(MetadataValue.Text(_)))
  )

  def spec = suite("Versioning")(
    test("the first version is 1.0, whatever the change kind") {
      assertTrue(
        Versioning.next(None, Major) == VersionNo(1, 0),
        Versioning.next(None, Minor) == VersionNo(1, 0)
      )
    },
    test("a content change increases the major number, an editorial change the minor number") {
      assertTrue(
        Versioning.next(Some(VersionNo(1, 0)), Major) == VersionNo(2, 0),
        Versioning.next(Some(VersionNo(1, 3)), Major) == VersionNo(2, 0),
        Versioning.next(Some(VersionNo(1, 0)), Minor) == VersionNo(1, 1),
        Versioning.next(Some(VersionNo(2, 1)), Minor) == VersionNo(2, 2)
      )
    },
    test("working states are counted continuously") {
      assertTrue(
        Versioning.nextWorkingState(Nil) == 1,
        Versioning.nextWorkingState(List(3)) == 4,
        Versioning.nextWorkingState(List(2, 5, 1)) == 6
      )
    },
    test("open versions are labelled with their working state, others with their number") {
      assertTrue(
        Versioning.label(VersionNo(1, 1), Draft, 3) == "1.1-draft.3",
        Versioning.label(VersionNo(1, 1), InReview, 3) == "1.1-draft.3",
        Versioning.label(VersionNo(1, 1), InApproval, 4) == "1.1-draft.4",
        Versioning.label(VersionNo(1, 1), Approved, 4) == "1.1",
        Versioning.label(VersionNo(2, 0), Published, 9) == "2.0"
      )
    },
    test("labels can be parsed back") {
      assertTrue(
        Versioning.parseLabel("1.1-draft.3") == Some((VersionNo(1, 1), Some(3))),
        Versioning.parseLabel("2.0") == Some((VersionNo(2, 0), None)),
        Versioning.parseLabel("2.0-draft.0").isEmpty,
        Versioning.parseLabel("2").isEmpty,
        Versioning.parseLabel("v2.0").isEmpty
      )
    },
    test("open, valid and frozen statuses partition the lifecycle as the business concept describes") {
      assertTrue(
        Versioning.open == Set(Draft, InReview, InApproval),
        Versioning.valid == Set(Published, ReviewDue),
        Versioning.frozen.contains(Approved),
        Versioning.frozen.contains(Withdrawn),
        (Versioning.open & Versioning.frozen).isEmpty,
        Versioning.open ++ Versioning.frozen == DocumentStatus.values.toSet,
        DocumentStatus.values.filter(Versioning.isEditable).toList == List(Draft)
      )
    },
    test("no lifecycle event turns a frozen version into an open one") {
      val reopening = for
        from  <- Versioning.frozen.toList
        event <- Lifecycle.allowedEvents(from).toList
        to    <- Lifecycle.transition(from, event).toOption.toList
        if Versioning.isOpen(to)
      yield (from, event, to)
      assertTrue(reopening.isEmpty)
    },
    test("the validity scope is the sorted set of scope values") {
      assertTrue(
        Versioning.scopeKey(Map.empty) == "",
        Versioning.scopeKey(scope()) == "",
        Versioning.scopeKey(scope(" ")) == "",
        Versioning.scopeKey(scope("DE", "AT")) == "AT|DE",
        Versioning.scopeKey(scope("AT", "DE ", "AT")) ==
          "AT|DE",
        Versioning.scopeKey(Map("scope" -> MetadataValue.Text("Group"))) == "Group"
      )
    }
  )

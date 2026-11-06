package dms.documents

import dms.documents.ChangeKind.*
import dms.documents.DocumentStatus.*
import dms.documents.metadata.MetadataValue
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

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
    test("the next version starts from the valid version, else from one withdrawn before it took effect") {
      val template                                                                = TemplateId.random()
      def version(major: Int, status: DocumentStatus, withdrawn: Boolean = false) =
        DocumentVersion(
          UUID.randomUUID(),
          DocumentId.random(),
          VersionNo(major, 0),
          1,
          status,
          Major,
          None,
          "",
          1,
          template,
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
          withdrawal =
            Option.when(withdrawn)(VersionWithdrawal(Instant.EPOCH, UserId("owner"), "Approved by mistake"))
        )
      val valid        = version(1, Published)
      val stopped      = version(2, Withdrawn, withdrawn = true)
      val outOfForce   = version(1, Withdrawn)
      val stoppedAgain = version(3, Withdrawn, withdrawn = true)
      val destroyed    = version(4, Destroyed, withdrawn = true)
      assertTrue(
        Versioning.startingPoint(List(valid, stopped)).contains(valid),
        Versioning.startingPoint(List(stopped, stoppedAgain)).contains(stoppedAgain),
        Versioning.startingPoint(List(stopped, destroyed)).contains(stopped),
        Versioning.startingPoint(List(outOfForce)).isEmpty,
        Versioning.startingPoint(Nil).isEmpty
      )
    },
    test("a draft is compared with what it started from; only without another version it is the first") {
      val template                                                              = TemplateId.random()
      def version(major: Int, status: DocumentStatus, released: Boolean = true) =
        DocumentVersion(
          UUID.randomUUID(),
          DocumentId.random(),
          VersionNo(major, 0),
          1,
          status,
          Major,
          None,
          "",
          1,
          template,
          1,
          TemplateContent(Nil),
          Map.empty,
          None,
          None,
          UserId("author"),
          Instant.EPOCH,
          Instant.EPOCH,
          Option.when(released)(UserId("approver")),
          Option.when(released)(Instant.EPOCH)
        )
      val draft      = version(3, Draft, released = false)
      val discarded  = version(2, Discarded, released = false)
      val valid      = version(1, Published)
      val outOfForce = version(1, Withdrawn)
      val destroyed  = version(2, Destroyed)
      assertTrue(
        Versioning.isFirst(draft, List(draft)),
        Versioning.isFirst(draft, List(discarded, draft)),
        !Versioning.isFirst(draft, List(valid, draft)),
        !Versioning.isFirst(draft, List(outOfForce, draft)),
        Versioning.basis(draft, List(draft)).isEmpty,
        Versioning.basis(draft, List(discarded, draft)).isEmpty,
        Versioning.basis(draft, List(valid, discarded, draft)).contains(valid),
        // The version it started from went out of force while the draft was open.
        Versioning.basis(draft, List(outOfForce, draft)).contains(outOfForce),
        Versioning.basis(draft, List(outOfForce, destroyed, draft)).contains(outOfForce)
      )
    },
    test("what is valid or on its way decides for the document, not what ended without taking effect") {
      val template = TemplateId.random()
      def version(
          major: Int,
          status: DocumentStatus,
          withdrawn: Boolean = false,
          published: Boolean = false
      ) =
        DocumentVersion(
          UUID.randomUUID(),
          DocumentId.random(),
          VersionNo(major, 0),
          1,
          status,
          Major,
          None,
          "",
          1,
          template,
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
          publishedAt = Option.when(published)(Instant.EPOCH),
          withdrawal =
            Option.when(withdrawn)(VersionWithdrawal(Instant.EPOCH, UserId("owner"), "Approved by mistake"))
        )
      val superseded   = version(1, Superseded, published = true)
      val valid        = version(2, Published, published = true)
      val otherScope   = version(3, ReviewDue, published = true)
      val neverValid   = version(4, Withdrawn, withdrawn = true)
      val draft        = version(5, Draft)
      val waiting      = version(5, Approved)
      val discarded    = version(5, Discarded)
      val ended        = version(2, Withdrawn, published = true)
      val archived     = version(1, Archived, published = true)
      val destroyed    = version(3, Destroyed, published = true)
      val firstStop    = version(1, Withdrawn, withdrawn = true)
      val firstStopped = version(2, Withdrawn, withdrawn = true)
      assertTrue(
        Versioning.governing(List(superseded, valid, neverValid)) == List(valid),
        Versioning.governing(List(superseded, valid, discarded)) == List(valid),
        // Several validity scopes, and what is on its way: each of them has a say.
        Versioning.governing(List(valid, otherScope, neverValid)) == List(valid, otherScope),
        Versioning.governing(List(valid, draft)) == List(valid, draft),
        Versioning.governing(List(valid, waiting)) == List(valid, waiting),
        Versioning.governing(List(draft)) == List(draft),
        // Nothing stands: the last one that was in force, else the latest.
        Versioning.governing(List(archived, ended, neverValid)) == List(ended),
        Versioning.governing(List(archived, destroyed, neverValid)) == List(archived),
        Versioning.governing(List(firstStop, firstStopped)) == List(firstStopped),
        Versioning.governing(Nil).isEmpty,
        // What names a document to someone who may not know all of it: in force first, then whatever is left.
        Versioning.naming(List(superseded, valid, otherScope, draft)).contains(otherScope),
        Versioning.naming(List(superseded, neverValid, draft)).contains(superseded),
        Versioning.naming(List(archived, ended)).contains(ended),
        Versioning.naming(List(neverValid, draft)).contains(draft),
        Versioning.naming(List(discarded, destroyed)).isEmpty,
        Versioning.naming(Nil).isEmpty
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

package dms.documents.metadata

import dms.documents.DocumentStatus
import dms.documents.DocumentStatus.*
import dms.documents.{LifecycleError, LifecycleEvent}
import zio.test.*

import java.time.LocalDate

/** Status-dependent required fields and the completeness check before a status change (DMS-33). */
object RequirednessSpec extends ZIOSpecDefault:
  import MetadataFixtures.*
  import MetadataValue as V

  private val profile = resolveOrFail(contract, serviceContract)

  /** Values the platform sets itself. */
  private val systemValues: Map[String, MetadataValue] = Map(
    "documentId"      -> V.Text("CON-2026-0001"),
    "documentClass"   -> V.Text("service-contract"),
    "version"         -> V.Text("0.1"),
    "status"          -> V.Selection("Draft"),
    "template"        -> V.Text("tpl-1"),
    "templateVersion" -> V.Text("1.0"),
    "retentionRule"   -> V.Text("contract-10y")
  )

  private val draft: Map[String, MetadataValue] = systemValues + ("title" -> V.Text("IT service contract"))

  /** Everything required for review. */
  private val reviewReady: Map[String, MetadataValue] = draft ++ Map(
    "owner"                -> V.PersonRef("legal-department"),
    "organizationalUnit"   -> V.Text("Legal"),
    "validFrom"            -> V.Date(LocalDate.of(2027, 1, 1)),
    "confidentiality"      -> V.Selection("Internal"),
    "language"             -> V.Text("de"),
    "containsPersonalData" -> V.YesNo(false),
    "contractPartner"      -> V.Text("ACME GmbH")
  )

  private def missing(issues: List[FieldIssue]): List[String] =
    issues.collect { case FieldIssue(field, _, IssueKind.MissingRequired(_)) => field }

  def spec = suite("Status-dependent requiredness")(
    test("a field can be optional in Draft and required for InReview") {
      assertTrue(
        Validation.validate(profile, draft, Draft).isEmpty,
        missing(Validation.validate(profile, draft, InReview)).contains("contractPartner")
      )
    },
    test("missing required fields are named with stable key and label, in profile order") {
      val issues = Validation.validate(profile, draft, InReview)
      assertTrue(
        missing(issues) == List(
          "owner",
          "organizationalUnit",
          "validFrom",
          "confidentiality",
          "language",
          "containsPersonalData",
          "contractPartner"
        ),
        issues
          .find(_.field == "contractPartner")
          .exists(i =>
            i.label == "Contract partner" && i.kind == IssueKind.MissingRequired(InReview) &&
              i.kind.code == "missing_required"
          )
      )
    },
    test("blank text and empty lists count as missing") {
      val values = reviewReady ++ Map("contractPartner" -> V.Text("  "), "owner" -> V.PersonRef(""))
      assertTrue(missing(Validation.validate(profile, values, InReview)) == List("owner", "contractPartner"))
    },
    test("fields required later are not required earlier") {
      assertTrue(
        Validation.validate(profile, reviewReady, InReview).isEmpty,
        missing(Validation.validate(profile, reviewReady, InApproval)) == List("serviceLevel"),
        missing(Validation.validate(profile, reviewReady + ("serviceLevel" -> V.Number(99)), Approved)) ==
          List("renditionChecksum")
      )
    },
    test("invalid values are reported together with missing fields") {
      val values = draft + ("serviceLevel" -> V.Number(500))
      val issues = Validation.validate(profile, values, InReview)
      assertTrue(
        issues.exists(i => i.field == "serviceLevel" && i.kind.code == "out_of_range"),
        missing(issues).contains("contractPartner")
      )
    },
    test("system fields can be left out when checking an author's input") {
      val authorInput = reviewReady -- systemValues.keySet
      assertTrue(
        Validation.validate(profile, authorInput, InReview, includeSystemFields = false).isEmpty,
        missing(Validation.validate(profile, authorInput, InReview)).contains("documentId")
      )
    },
    test("submission is blocked while required fields are missing") {
      val blocked = Validation.checkTransition(profile, draft, Draft, LifecycleEvent.Submit)
      assertTrue(blocked match
        case Left(TransitionBlocked.Incomplete(InReview, issues)) =>
          missing(issues).contains("contractPartner")
        case _ => false)
    },
    test("a complete document can be submitted") {
      assertTrue(
        Validation.checkTransition(profile, reviewReady, Draft, LifecycleEvent.Submit) == Right(InReview)
      )
    },
    test("a transition the lifecycle does not allow is reported as such") {
      assertTrue(
        Validation.checkTransition(profile, reviewReady, Draft, LifecycleEvent.Publish) ==
          Left(
            TransitionBlocked.NotAllowed(LifecycleError.TransitionNotAllowed(Draft, LifecycleEvent.Publish))
          )
      )
    },
    suite("submission (DMS-147)")({
      def requiring(statuses: Set[DocumentStatus]) = resolveOrFail(
        contract,
        serviceContract.copy(
          version = 2,
          fields = FieldBinding(contractValue.key, 1, statuses) :: serviceContract.fields
        )
      )
      val filled = reviewReady + ("serviceLevel" -> MetadataValue.Number(99))
      def blockedBy(p: MetadataProfile, values: Map[String, MetadataValue]) =
        Validation.checkSubmission(p, values, Draft) match
          case Left(TransitionBlocked.Incomplete(InReview, issues)) => issues.map(i => (i.field, i.kind))
          case _                                                    => Nil
      List(
        test("a field required only in a later status blocks it and names that status") {
          assertTrue(
            blockedBy(requiring(Set(Published)), filled) ==
              List("contractValue" -> IssueKind.MissingRequired(Published)),
            blockedBy(profile, reviewReady) ==
              List("serviceLevel" -> IssueKind.MissingRequired(InApproval)),
            blockedBy(requiring(Set(Superseded)), filled) ==
              List("contractValue" -> IssueKind.MissingRequired(Superseded))
          )
        },
        test("a field required now and later is reported once, for now") {
          val issues = blockedBy(requiring(Requiredness.from(InReview)), filled)
          assertTrue(issues == List("contractValue" -> IssueKind.MissingRequired(InReview)))
        },
        test("fields the platform adds later and statuses outside the regular path do not block it") {
          assertTrue(
            Validation.checkSubmission(profile, filled, Draft) == Right(InReview),
            Validation.checkSubmission(requiring(Set(Withdrawn)), filled, Draft) == Right(InReview)
          )
        },
        test("a system field the platform never sets blocks it, one it still sets does not (DMS-148)") {
          val numbered = profile.copy(fields =
            profile.fields.map(f =>
              if f.key.value == CoreFields.DocumentNumber then f.copy(requiredIn = Set(Published)) else f
            )
          )
          assertTrue(
            blockedBy(numbered, filled) ==
              List(CoreFields.DocumentNumber -> IssueKind.MissingRequired(Published)),
            Validation.checkSubmission(
              numbered,
              filled + (CoreFields.DocumentNumber -> V.Text("P-1")),
              Draft
            ) ==
              Right(InReview)
          )
        },
        test("what the caller adds is reported with the rest, once per field") {
          val extra = FieldIssue("contractPartner", "Partner", IssueKind.MissingRequired(Approved))
          val other = FieldIssue("termMonths", "Term", IssueKind.MissingRequired(Approved))
          val found =
            Validation.checkSubmission(profile, reviewReady - "contractPartner", Draft, List(extra, other))
          assertTrue(found match
            case Left(TransitionBlocked.Incomplete(_, issues)) =>
              issues.map(_.field).toSet == Set("contractPartner", "serviceLevel", "termMonths") &&
              issues.count(_.field == "contractPartner") == 1
            case _ => false)
        }
      )
    }*),
    test("a child class can add requiredness for an inherited field") {
      val strict = serviceContract.copy(
        version = 2,
        fields = FieldBinding(contractValue.key, 1, Requiredness.from(InReview)) :: serviceContract.fields
      )
      val strictProfile = resolveOrFail(contract, strict)
      assertTrue(
        missing(Validation.validate(strictProfile, reviewReady, InReview)) == List("contractValue"),
        Validation.validate(profile, reviewReady, InReview).isEmpty
      )
    },
    test("a new profile version does not change documents on the old version") {
      val v2 = contract.copy(
        version = 2,
        fields = contract.fields.map(b =>
          if b.key == contractValue.key then b.copy(requiredIn = Requiredness.from(InReview)) else b
        )
      )
      val oldProfile = resolveOrFail(contract)
      val newProfile = resolveOrFail(v2)
      val values     = reviewReady.removed("serviceLevel")
      assertTrue(
        Validation.validate(oldProfile, values, InReview).isEmpty,
        missing(Validation.validate(newProfile, values, InReview)) == List("contractValue"),
        oldProfile.field("contractValue").exists(_.requiredIn.isEmpty)
      )
    }
  )

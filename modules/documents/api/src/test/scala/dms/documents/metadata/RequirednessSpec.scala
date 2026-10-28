package dms.documents.metadata

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

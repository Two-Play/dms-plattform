package dms.documents.metadata

import dms.documents.DocumentStatus
import zio.test.*

import java.time.LocalDate
import java.util.UUID

object ValidationSpec extends ZIOSpecDefault:
  import MetadataFixtures.*
  import MetadataValue as V

  private def issues(definition: FieldDefinition, value: MetadataValue): List[IssueKind] =
    Validation.checkField(definition, value)

  private def field(fieldType: FieldType, multiple: Boolean = false) =
    FieldDefinition(key("someField"), 1, "Some field", None, fieldType, multiple)

  def spec = suite("Validation")(
    suite("types")(
      test("text: maximum length and pattern") {
        val t = field(FieldType.Text(maxLength = Some(5), pattern = Some("[A-Z]+")))
        assertTrue(
          issues(t, V.Text("ABC")).isEmpty,
          issues(t, V.Text("ABCDEF")).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(t, V.Text("abc")) == List(IssueKind.PatternMismatch("[A-Z]+")),
          issues(t, V.Number(1)) == List(IssueKind.WrongType("Text", "Number"))
        )
      },
      test("number: range and decimal places") {
        assertTrue(
          issues(serviceLevel, V.Number(BigDecimal("99.5"))).isEmpty,
          issues(serviceLevel, V.Number(BigDecimal("99.50"))).isEmpty,
          issues(serviceLevel, V.Number(BigDecimal("99.55"))).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(serviceLevel, V.Number(BigDecimal(101))).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(serviceLevel, V.Number(BigDecimal(-1))).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(serviceLevel, V.Text("50")) == List(IssueKind.WrongType("Number", "Text"))
        )
      },
      test("amount with currency: allowed currencies and minimum") {
        assertTrue(
          issues(contractValue, V.Amount(BigDecimal("1200.00"), "EUR")).isEmpty,
          issues(contractValue, V.Amount(BigDecimal(1), "CHF")) == List(IssueKind.UnknownCurrency("CHF")),
          issues(contractValue, V.Amount(BigDecimal(1), "eur")) == List(IssueKind.UnknownCurrency("eur")),
          issues(contractValue, V.Amount(BigDecimal(-1), "EUR")).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(field(FieldType.Amount()), V.Amount(BigDecimal(1), "CHF")).isEmpty,
          issues(contractValue, V.Number(1)) == List(IssueKind.WrongType("Amount", "Number"))
        )
      },
      test("date: range") {
        val d = field(FieldType.Date(Some(LocalDate.of(2026, 1, 1)), Some(LocalDate.of(2026, 12, 31))))
        assertTrue(
          issues(d, V.Date(LocalDate.of(2026, 6, 1))).isEmpty,
          issues(d, V.Date(LocalDate.of(2025, 12, 31))).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(d, V.Date(LocalDate.of(2027, 1, 1))).exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(d, V.Text("2026-06-01")) == List(IssueKind.WrongType("Date", "Text"))
        )
      },
      test("selection: known, unknown and removed options") {
        assertTrue(
          issues(noticePeriodV2, V.Selection("3m")).isEmpty,
          issues(noticePeriodV2, V.Selection("12m")) == List(IssueKind.UnknownOption("12m")),
          issues(noticePeriodV2, V.Selection("6m")) == List(IssueKind.RetiredOption("6m")),
          issues(noticePeriodV1, V.Selection("6m")).isEmpty
        )
      },
      test("yes/no, person and document references") {
        assertTrue(
          issues(field(FieldType.YesNo), V.YesNo(true)).isEmpty,
          issues(field(FieldType.PersonRef), V.PersonRef("user-1")).isEmpty,
          issues(field(FieldType.DocumentRef), V.DocumentRef(UUID.randomUUID())).isEmpty,
          issues(field(FieldType.YesNo), V.Text("yes")) == List(IssueKind.WrongType("YesNo", "Text")),
          issues(field(FieldType.PersonRef), V.DocumentRef(UUID.randomUUID())) ==
            List(IssueKind.WrongType("PersonRef", "DocumentRef")),
          issues(field(FieldType.DocumentRef), V.PersonRef("x")) ==
            List(IssueKind.WrongType("DocumentRef", "PersonRef"))
        )
      },
      test("multiple values: lists are required and every element is checked") {
        val tags = field(FieldType.Text(maxLength = Some(3)), multiple = true)
        assertTrue(
          issues(tags, V.Many(List(V.Text("a"), V.Text("b")))).isEmpty,
          issues(tags, V.Many(List(V.Text("a"), V.Text("long"))))
            .exists(_.isInstanceOf[IssueKind.OutOfRange]),
          issues(tags, V.Text("a")) == List(IssueKind.WrongType("list of Text", "Text")),
          issues(partner, V.Many(List(V.Text("a")))) == List(IssueKind.WrongType("Text", "Many"))
        )
      }
    ),
    suite("profiles")(
      test("values are checked against the profile, unknown fields are reported by key") {
        val profile = resolveOrFail(contract, serviceContract)
        val result  = Validation.checkValues(
          profile,
          Map(
            "contractPartner" -> V.Text("ACME"),
            "serviceLevel"    -> V.Number(120),
            "confidentiality" -> V.Selection("Secret"),
            "costCenter"      -> V.Text("4711")
          )
        )
        assertTrue(
          result.map(i => (i.field, i.kind.code)) == List(
            ("confidentiality", "unknown_option"),
            ("serviceLevel", "out_of_range"),
            ("costCenter", "unknown_field")
          ),
          result.find(_.field == "serviceLevel").exists(_.label == "Service level")
        )
      },
      test("a removed option stays valid for documents on the old profile version") {
        val v2 = contract.copy(
          version = 2,
          fields = contract.fields.map { b =>
            if b.key == noticePeriodV2.key then b.copy(version = 2) else b
          }
        )
        val values = Map("noticePeriod" -> V.Selection("6m"))
        assertTrue(
          Validation.checkValues(resolveOrFail(contract), values).isEmpty,
          Validation.checkValues(resolveOrFail(v2), values).map(_.kind) == List(IssueKind.RetiredOption("6m"))
        )
      },
      test("core field values are validated, e.g. language and checksum formats") {
        val profile = resolveOrFail(contract)
        val result  = Validation.checkValues(
          profile,
          Map(
            "language"          -> V.Text("en-GB"),
            "renditionChecksum" -> V.Text("not-a-hash"),
            "tags"              -> V.Many(List(V.Text("hr"), V.Text("onboarding")))
          )
        )
        assertTrue(result.map(_.field) == List("renditionChecksum"))
      },
      test("confidential versions need allowed groups from review on; group keys are checked (DMS-101)") {
        val profile = resolveOrFail(contract)
        def groupIssues(confidentiality: String, groups: List[String], target: DocumentStatus) =
          Validation
            .validate(
              profile,
              Map(
                "confidentiality" -> V.Selection(confidentiality),
                "allowedGroups"   -> V.Many(groups.map(V.Text(_)))
              ),
              target
            )
            .filter(_.field == "allowedGroups")
            .map(_.kind)
        assertTrue(
          groupIssues("Confidential", Nil, DocumentStatus.Draft).isEmpty,
          groupIssues("Confidential", Nil, DocumentStatus.InReview) ==
            List(IssueKind.MissingRequired(DocumentStatus.InReview)),
          groupIssues("StrictlyConfidential", List(" "), DocumentStatus.Approved) ==
            List(IssueKind.MissingRequired(DocumentStatus.Approved)),
          groupIssues("Confidential", List("board"), DocumentStatus.InReview).isEmpty,
          groupIssues("Internal", Nil, DocumentStatus.Published).isEmpty,
          groupIssues("Confidential", List("Board Members"), DocumentStatus.Draft) ==
            List(IssueKind.PatternMismatch(CoreFields.GroupKeyPattern)),
          CoreFields.isRestricted(Map("confidentiality" -> V.Selection("StrictlyConfidential"))),
          !CoreFields.isRestricted(Map("confidentiality" -> V.Selection("Public")))
        )
      }
    )
  )

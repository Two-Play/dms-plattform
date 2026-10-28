package dms.templates.core

import dms.documents.DocumentStatus
import dms.documents.metadata.*
import dms.kernel.{TemplateId, UserId}
import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*

import java.time.{Instant, LocalDate}

/** A realistic service contract: a class profile (core fields plus contract fields) and a template of about
  * twelve printed pages, modelled on the 12-page contract of the rendering spike (DMS-23): numbered clauses,
  * conditions, a repeated service list, annex tables and computed fields.
  */
object PreviewFixtures:

  private def field(
      key: String,
      label: String,
      fieldType: FieldType,
      required: Boolean = false,
      multiple: Boolean = false
  ): ProfileField =
    ProfileField(
      FieldDefinition(FieldKey.unsafe(key), 1, label, None, fieldType, multiple),
      if required then Requiredness.from(DocumentStatus.InReview) else Set.empty,
      Some(ClassKey.unsafe("contract"))
    )

  val profile: MetadataProfile = MetadataProfile(
    DocumentClassId.random(),
    ClassKey.unsafe("contract"),
    1,
    List(ClassKey.unsafe("contract")),
    CoreFields.fields ++ List(
      field("clientName", "Client name", FieldType.Text(maxLength = Some(200)), required = true),
      field("clientAddress", "Client address", FieldType.Text()),
      field("monthlyFee", "Monthly fee", FieldType.Amount(min = Some(0), currencies = List("EUR")), true),
      field("termMonths", "Term in months", FieldType.Number(min = Some(1), max = Some(120))),
      field(
        "termType",
        "Term",
        FieldType.Selection(List(SelectOption("fixed", "Fixed term"), SelectOption("open", "Open-ended"))),
        required = true
      ),
      field("startDate", "Start date", FieldType.Date(), required = true),
      field("endDate", "End date", FieldType.Date()),
      field("noticePeriod", "Notice period", FieldType.Text()),
      field("services", "Services", FieldType.Text(), multiple = true),
      field("international", "International", FieldType.YesNo),
      field(
        "slaLevel",
        "Service level",
        FieldType.Selection(
          List(
            SelectOption("basic", "Basic"),
            SelectOption("standard", "Standard"),
            SelectOption("premium", "Premium")
          )
        )
      )
    )
  )

  private val words =
    ("the provider shall perform the services with the care of a prudent business person in accordance with " +
      "the state of the art and the recognised rules of good practice including all documentation reporting " +
      "and cooperation duties agreed between the parties unless otherwise stated in the annexes").split(' ')

  /** A sentence of `n` words, deterministic per seed. */
  private def sentence(seed: Int, n: Int): String =
    val ws = (0 until n).map(i => words((seed * 7 + i * 3) % words.length))
    ws.head.capitalize + ws.tail.mkString(" ", " ", ".")

  private def clause(no: Int): Section =
    val subclauses = (1 to 6).map { k =>
      List(
        Paragraph(
          List(
            Text(sentence(no * 10 + k, 28) + " "),
            Text("The client ", Set(Mark.Bold)),
            Placeholder("clientName"),
            Text(" " + sentence(no * 10 + k + 1, 26))
          )
        )
      )
    }.toList
    val extra =
      if no % 3 == 0 then
        List(
          Conditional(
            "termType == 'fixed'",
            List(
              Paragraph(List(Text("The term ends on "), Placeholder("endDate", Some("date:long")), Text(".")))
            ),
            List(Paragraph(List(Text("Notice period: "), Placeholder("noticePeriod"), Text("."))))
          )
        )
      else if no % 4 == 0 then
        List(Conditional("international", List(Paragraph(List(Text(sentence(no, 40)))))))
      else Nil
    Section(
      SectionId(s"clause$no"),
      Some(s"$no. Clause $no"),
      locked = no % 2 == 0,
      OrderedList(subclauses) :: extra
    )

  private def cell(inlines: Inline*): List[Block] = List(Paragraph(inlines.toList))

  private val annex: Section = Section(
    SectionId("annex"),
    Some("Annex A: Services and service levels"),
    locked = false,
    List(
      PageBreak,
      Heading(2, List(Text("A.1 Services"))),
      Repeat(
        "services",
        "service",
        List(BulletList(List(List(Paragraph(List(Placeholder("service"), Text(" – " + sentence(3, 12))))))))
      ),
      Heading(2, List(Text("A.2 Service levels"))),
      Table(
        List(
          cell(Text("Service")),
          cell(Text("Availability")),
          cell(Text("Response")),
          cell(Text("Level"))
        ) ::
          (1 to 30).toList.map(i =>
            List(
              cell(Text(s"Service component $i")),
              cell(Text(s"${99 + i % 10 / 10.0} %")),
              cell(Text(s"${i % 8 + 1} h")),
              cell(Placeholder("slaLevel"))
            )
          )
      ),
      Heading(2, List(Text("A.3 Fees"))),
      Table(
        List(
          List(cell(Text("Item")), cell(Text("Amount"))),
          List(cell(Text("Monthly fee")), cell(Placeholder("monthlyFee", Some("amount")))),
          List(cell(Text("Term (months)")), cell(Placeholder("termMonths", Some("number")))),
          List(cell(Text("Total contract value")), cell(Placeholder("totalValue", Some("number")))),
          List(cell(Text("Annual fee")), cell(Placeholder("annualFee", Some("number"))))
        )
      ),
      Conditional(
        "totalValue > 250000.0",
        List(
          Paragraph(
            List(Text("The contract value requires approval by the management board.", Set(Mark.Italic)))
          )
        )
      )
    )
  )

  val content: TemplateContent = TemplateContent(
    Section(
      SectionId("parties"),
      Some("IT Service Agreement"),
      locked = true,
      List(
        Heading(1, List(Text("IT Service Agreement"))),
        Paragraph(
          List(
            Text("Between "),
            Placeholder("clientName"),
            Text(", "),
            Placeholder("clientAddress"),
            LineBreak,
            Text("and Sample Managed Services GmbH, starting "),
            Placeholder("startDate", Some("date:long")),
            Text(".")
          )
        )
      )
    ) :: (1 to 16).toList.map(clause) ++ List(annex),
    computed = List(
      ComputedField("totalValue", "monthlyFee.amount * termMonths"),
      ComputedField("annualFee", "monthlyFee.amount * 12.0")
    )
  )

  val version: TemplateVersion = TemplateVersion(
    TemplateId.random(),
    1,
    "IT Service Agreement",
    None,
    profile.classId.value,
    1,
    TemplateStatus.Released,
    content,
    UserId("designer-1"),
    Instant.parse("2026-10-28T10:00:00Z")
  )

  def compile(): CompiledTemplate =
    TemplateCompiler
      .compile(content, ProfileSchema.from(profile))
      .fold(issues => throw AssertionError(issues.map(_.show).mkString("; ")), identity)

  lazy val template: PreviewTemplate = PreviewTemplate(version, profile, compile())

  val values: Map[String, MetadataValue] = Map(
    "title"                -> MetadataValue.Text("IT Service Agreement ACME"),
    "owner"                -> MetadataValue.PersonRef("legal-department"),
    "organizationalUnit"   -> MetadataValue.Text("Procurement"),
    "validFrom"            -> MetadataValue.Date(LocalDate.of(2027, 1, 1)),
    "confidentiality"      -> MetadataValue.Selection("Internal"),
    "language"             -> MetadataValue.Text("en"),
    "containsPersonalData" -> MetadataValue.YesNo(false),
    "clientName"           -> MetadataValue.Text("ACME Municipal Utilities Ltd."),
    "clientAddress"        -> MetadataValue.Text("Harbour Road 12, 20457 Hamburg"),
    "monthlyFee"           -> MetadataValue.Amount(BigDecimal("12500"), "EUR"),
    "termMonths"           -> MetadataValue.Number(36),
    "termType"             -> MetadataValue.Selection("fixed"),
    "startDate"            -> MetadataValue.Date(LocalDate.of(2027, 1, 1)),
    "endDate"              -> MetadataValue.Date(LocalDate.of(2029, 12, 31)),
    "services" -> MetadataValue.Many((1 to 12).toList.map(i => MetadataValue.Text(s"Managed service $i"))),
    "international" -> MetadataValue.YesNo(true),
    "slaLevel"      -> MetadataValue.Selection("premium")
  )

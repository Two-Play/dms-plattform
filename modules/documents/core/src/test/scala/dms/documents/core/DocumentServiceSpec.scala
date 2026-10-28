package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.*
import dms.documents.DocumentStatus.*
import dms.documents.LifecycleEvent.*
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** Creating documents from released templates and the version lifecycle (migration V9) against a
  * Testcontainers database. Templates come from a stub of the template module.
  */
object DocumentServiceSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, service: DocumentService, audit: Ref[List[AuditEvent]])

  /** A tenant with the contract class, a released template version (1), a template draft (2) and a released
    * version (3) that takes the contract partner from the partner master.
    */
  final case class Fixture(tenant: TenantId, classId: DocumentClassId, templateId: TemplateId)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private final class RecordingAuditTrail(events: Ref[List[AuditEvent]]) extends AuditTrail:
    def record(event: AuditEvent): IO[DbError, AuditRecordId] =
      events.update(_ :+ event).as(AuditRecordId(0L))
    def verify(tenant: TenantId): IO[DbError, VerificationReport] =
      ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None))

  /** Content codecs for the stub; the real format lives in templates-core (`ContentJson`). */
  private object Codecs:
    given JsonCodecConfiguration =
      JsonCodecConfiguration(sumTypeHandling =
        JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
      )
    given JsonCodec[SectionId]       = JsonCodec.string.transform(SectionId(_), _.value)
    given JsonCodec[Mark]            = JsonCodec.string.transform(Mark.valueOf, _.toString)
    given JsonCodec[Inline]          = DeriveJsonCodec.gen
    given JsonCodec[Block]           = DeriveJsonCodec.gen
    given JsonCodec[Section]         = DeriveJsonCodec.gen
    given JsonCodec[ComputedField]   = DeriveJsonCodec.gen
    given JsonCodec[ReferenceSource] =
      JsonCodec.string.transform(
        k => ReferenceSource.parse(k).getOrElse(ReferenceSource.PartnerMaster),
        _.key
      )
    given JsonCodec[ReferenceMapping] = DeriveJsonCodec.gen
    given JsonCodec[ReferenceField]   = DeriveJsonCodec.gen
    given JsonCodec[TemplateContent]  = DeriveJsonCodec.gen

    given JsonCodec[UserId]             = JsonCodec.string.transform(UserId(_), _.value)
    given JsonCodec[Inline.Placeholder] = DeriveJsonCodec.gen
    given JsonCodec[PathStep]           = DeriveJsonCodec.gen
    given JsonCodec[DeviationLocation]  = DeriveJsonCodec.gen
    given JsonCodec[DeviationChange]    = DeriveJsonCodec.gen
    given JsonCodec[DeviationRequest]   = DeriveJsonCodec.gen
    given JsonCodec[Deviation]          = DeriveJsonCodec.gen
    given JsonCodec[DeviationReport]    = DeriveJsonCodec.gen

  /** Template versions by id and number; computes `totalValue = monthlyFee.amount * termMonths`, which has no
    * value without `termMonths`.
    */
  private object Templates extends TemplateSource:
    import Codecs.given
    val versions = ConcurrentHashMap[(TemplateId, Int), TemplateVersion]()

    def version(id: TemplateId, version: Int): IO[DocumentError, TemplateVersion] =
      ZIO
        .succeed(Option(versions.get((id, version))))
        .someOrFail(DocumentError.NotFound(s"template ${id.value} version $version does not exist"))

    def check(template: TemplateVersion, content: TemplateContent): IO[DocumentError, List[String]] =
      ZIO.succeed(
        TemplateContent.placeholders(content).filter(_.startsWith("unknown")).map(p => s"unknown field $p")
      )

    def computed(
        template: TemplateVersion,
        content: TemplateContent,
        values: Map[String, MetadataValue]
    ): IO[DocumentError, Map[String, MetadataValue]] =
      val fee  = values.get("monthlyFee").collect { case MetadataValue.Amount(a, _) => a }
      val term = values.get("termMonths").collect { case MetadataValue.Number(n) => n }
      ZIO.succeed(
        content.computed.flatMap {
          case ComputedField("totalValue", _) =>
            fee.zip(term).map((f, t) => "totalValue" -> MetadataValue.Number(f * t))
          case _ => None
        }.toMap
      )

    def missingComputed(
        template: TemplateVersion,
        content: TemplateContent,
        values: Map[String, MetadataValue]
    ): IO[DocumentError, Map[String, List[String]]] =
      ZIO.succeed(
        content.computed.collect {
          case ComputedField("totalValue", _) if !values.contains("termMonths") =>
            "totalValue" -> List("termMonths")
        }.toMap
      )

    def encode(content: TemplateContent): String              = content.toJson
    def decode(json: String): Either[String, TemplateContent] = json.fromJson[TemplateContent]

    // The column default of migration V10 is the empty report of the real format, {"format":1,"deviations":[]}.
    def encodeReport(report: DeviationReport): String               = report.toJson
    def decodeReport(json: String): Either[String, DeviationReport] = json.fromJson[DeviationReport]

  private val env: ZLayer[Any, Throwable, Env] = ZLayer.scoped(
    for
      c <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _      <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app    <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
      events <- Ref.make(List.empty[AuditEvent])
      audit = RecordingAuditTrail(events)
    yield Env(Db(app), Db(owner), DocumentService(audit, DocumentClassService(audit), Templates), events)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val approver = UserId("approver-1")

  private val content = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = true,
        List(Block.Paragraph(List(Inline.Text("Between "), Inline.Placeholder("contractPartner"))))
      ),
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Terms"))))
      )
    ),
    computed = List(ComputedField("totalValue", "monthlyFee.amount * termMonths"))
  )

  private val Parties = SectionId("parties")

  /** The template content with standard text added to the locked section `parties`. */
  private val deviating = content.copy(sections =
    content.sections.map(s =>
      if s.locked then
        s.copy(blocks =
          List(
            Block.Paragraph(
              List(
                Inline.Text("Between "),
                Inline.Placeholder("contractPartner"),
                Inline.Text(" and its affiliates")
              )
            )
          )
        )
      else s
    )
  )

  private val PartnerMaster = ValueOrigin.System("partner-master")

  /** Version 3: the contract partner is a reference field (name and identifier from the partner master). */
  private val partnerContent = content.copy(references =
    List(
      ReferenceField(
        "contractPartner",
        "Contract partner",
        ReferenceSource.PartnerMaster,
        List(ReferenceMapping("id", "partnerId"), ReferenceMapping("name", "contractPartner"))
      )
    )
  )

  private def fixture: ZIO[Env, Any, Fixture] =
    val tenant     = TenantId.random()
    val templateId = TemplateId.random()
    for
      e <- ZIO.service[Env]
      _ <- e.owner.transactSystem(
        Db.sql(sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run())
      )
      classes = DocumentClassService(RecordingAuditTrail(e.audit))
      cls <- e.app.transact(tenant)(
        classes.createField(
          "contractPartner",
          FieldDraft("Contract partner", None, FieldType.Text()),
          admin
        ) *>
          classes.createField("monthlyFee", FieldDraft("Monthly fee", None, FieldType.Amount()), admin) *>
          classes.createField("termMonths", FieldDraft("Term in months", None, FieldType.Number()), admin) *>
          classes.createField("partnerId", FieldDraft("Partner number", None, FieldType.Text()), admin) *>
          classes.createClass(
            "contract",
            None,
            ClassDraft(
              "Contract",
              None,
              List(
                FieldBinding(FieldKey.unsafe("contractPartner"), 1, Requiredness.from(InReview)),
                FieldBinding(FieldKey.unsafe("monthlyFee"), 1),
                FieldBinding(FieldKey.unsafe("termMonths"), 1),
                FieldBinding(FieldKey.unsafe("partnerId"), 1)
              )
            ),
            admin
          )
      )
      stored        = Jsonb(Templates.encode(content))
      storedPartner = Jsonb(Templates.encode(partnerContent))
      _ <- e.owner.transactSystem(Db.sql {
        val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                      values (${templateId.value}, ${tenant.value}, ${"designer"})""".update.run()
        val _ = sql"""insert into dms.template_version
                        (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                         content, created_by, status_changed_by)
                      values (${tenant.value}, ${templateId.value}, 1, ${"Contract"}, ${cls.classId.value}, 1,
                              ${"Testing"}, $stored, ${"designer"}, ${"designer"})""".update.run()
        val _ =
          sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
                      where template_id = ${templateId.value}""".update.run()
        // One open version per template: version 3 is tested and released after version 1.
        val _ = sql"""insert into dms.template_version
                        (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                         content, created_by, status_changed_by)
                      values (${tenant.value}, ${templateId.value}, 3, ${"Contract"}, ${cls.classId.value}, 1,
                              ${"Testing"}, $storedPartner, ${"designer"}, ${"designer"})""".update.run()
        val _ =
          sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
                      where template_id = ${templateId.value} and version = 3""".update.run()
        sql"""insert into dms.template_version
                (tenant_id, template_id, version, name, document_class_id, document_class_version, status, content,
                 created_by, status_changed_by)
              values (${tenant.value}, ${templateId.value}, 2, ${"Contract"}, ${cls.classId.value}, 1, ${"Draft"},
                      $stored, ${"designer"}, ${"designer"})""".update.run()
      })
      _ = List(1 -> TemplateStatus.Released, 2 -> TemplateStatus.Draft, 3 -> TemplateStatus.Released)
        .foreach { (v, status) =>
          Templates.versions.put(
            (templateId, v),
            TemplateVersion(
              templateId,
              v,
              "Contract",
              None,
              cls.classId.value,
              1,
              status,
              if v == 3 then partnerContent else content,
              UserId("designer"),
              Instant.EPOCH
            )
          )
        }
    yield Fixture(tenant, cls.classId, templateId)

  private def as[A](tenant: TenantId)(f: DocumentService => IO[DocumentError, A]) =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(f(e.service)))

  private def manual(values: (String, MetadataValue)*): Map[String, FieldEntry] =
    values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual)).toMap

  private val initialValues = manual(
    "contractPartner" -> MetadataValue.Text("ACME GmbH"),
    "monthlyFee"      -> MetadataValue.Amount(BigDecimal(100), "EUR"),
    "termMonths"      -> MetadataValue.Number(12)
  )

  /** The core fields required from review on. */
  private val reviewValues = initialValues ++ manual(
    "owner"                -> MetadataValue.PersonRef("owner-1"),
    "organizationalUnit"   -> MetadataValue.Text("Legal"),
    "validFrom"            -> MetadataValue.Date(LocalDate.of(2027, 1, 1)),
    "confidentiality"      -> MetadataValue.Selection("Internal"),
    "language"             -> MetadataValue.Text("de"),
    "retentionRule"        -> MetadataValue.Text("contract-10y"),
    "containsPersonalData" -> MetadataValue.YesNo(false)
  )

  private def create(f: Fixture, values: Map[String, FieldEntry] = initialValues) =
    as(f.tenant)(_.create(NewDocument(f.templateId, 1, "Service contract ACME", values), author))

  private def outbox(document: DocumentId): ZIO[Env, DbError, List[String]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"select event_type from dms.outbox_event where aggregate_id = ${document.value} order by id"
            .query[String]
            .run()
            .toList
        )
      )
    )

  /** Brings a new document through review and approval to publication. */
  private def published(f: Fixture): ZIO[Env, Any, DocumentId] =
    for
      created <- create(f, reviewValues)
      (doc, v) = created
      _ <- as(f.tenant)(_.submit(doc.id, author))
      _ <- as(f.tenant)(_.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
      _ <- as(f.tenant)(_.attachRendition(v.id, Rendition("renditions/1.pdf", Some("v1"), "a" * 64), author))
      _ <- as(f.tenant)(_.changeStatus(v.id, Approve, approver))
      _ <- as(f.tenant)(_.changeStatus(v.id, Publish, approver))
    yield doc.id

  def spec = suite("DocumentService")(
    test(
      "a document is created from a released template, bound to its version, with the origin of every value"
    ) {
      for
        f       <- fixture
        created <- create(f)
        (doc, version) = created
        details <- as(f.tenant)(_.get(doc.id))
        stored = details.versions.head
        events <- outbox(doc.id)
        states <- ZIO.serviceWithZIO[Env](
          _.app.transact(f.tenant)(
            Db.sql(
              sql"select working_state from dms.document_working_state where version_id = ${version.id}"
                .query[Int]
                .run()
                .toList
            )
          )
        )
      yield assertTrue(
        version.label == "1.0-draft.1",
        version.status == Draft,
        version.content == content,
        doc.templateId == f.templateId,
        doc.templateVersion == 1,
        doc.classId == f.classId,
        doc.authorId == author,
        stored == version.copy(createdAt = stored.createdAt, updatedAt = stored.updatedAt),
        stored.values("contractPartner").origin == ValueOrigin.Manual,
        stored.values("title") == FieldEntry(MetadataValue.Text("Service contract ACME"), ValueOrigin.Manual),
        stored.values("documentId") == FieldEntry(
          MetadataValue.Text(doc.id.value.toString),
          ValueOrigin.Platform
        ),
        stored.values("version").value == MetadataValue.Text("1.0"),
        stored.values("templateVersion").value == MetadataValue.Text("1"),
        stored.values("totalValue") == FieldEntry(MetadataValue.Number(1200), ValueOrigin.Computed),
        events == List("document.created"),
        states == List(1)
      )
    },
    test("only released template versions can be used") {
      for
        f     <- fixture
        draft <- as(f.tenant)(
          _.create(NewDocument(f.templateId, 2, "Contract", initialValues), author)
        ).either
        none <- as(f.tenant)(_.create(NewDocument(f.templateId, 9, "Contract", initialValues), author)).either
      yield assertTrue(
        draft.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        none.left.exists(_.isInstanceOf[DocumentError.NotFound])
      )
    },
    test(
      "field values are validated against the class profile; platform and computed values cannot be entered"
    ) {
      for
        f      <- fixture
        result <- create(
          f,
          manual(
            "monthlyFee" -> MetadataValue.Text("a lot"),
            "unknown"    -> MetadataValue.Text("x"),
            "documentId" -> MetadataValue.Text("mine")
          ) + ("totalValue" -> FieldEntry(MetadataValue.Number(1), ValueOrigin.Computed))
        ).either
        noTitle <- as(f.tenant)(_.create(NewDocument(f.templateId, 1, " ", initialValues), author)).either
      yield assertTrue(
        result.left.toOption.collect { case DocumentError.InvalidValues(issues) =>
          issues.map(i => i.field -> i.kind.code)
        } == Some(
          List(
            "documentId" -> "not_editable",
            "totalValue" -> "not_editable",
            "monthlyFee" -> "wrong_type",
            "totalValue" -> "unknown_field",
            "unknown"    -> "unknown_field"
          )
        ),
        noTitle.left.toOption.collect { case DocumentError.InvalidValues(issues) => issues.map(_.field) } ==
          Some(List("title"))
      )
    },
    test("a draft change is a new working state; values are replaced and computed values recomputed") {
      for
        f       <- fixture
        created <- create(f)
        (doc, _) = created
        changed  = content.copy(sections =
          content.sections.map(s =>
            if s.locked then s else s.copy(blocks = List(Block.Paragraph(List(Inline.Text("New terms")))))
          )
        )
        updated <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(
              Some("Renamed"),
              initialValues + ("termMonths" -> FieldEntry(MetadataValue.Number(24), ValueOrigin.Manual)) +
                ("contractPartner" -> FieldEntry(MetadataValue.Text("ACME AG"), ValueOrigin.System("crm"))),
              Some(changed)
            ),
            author
          )
        )
        details <- as(f.tenant)(_.get(doc.id))
        audit   <- ZIO.serviceWithZIO[Env](_.audit.get)
      yield assertTrue(
        updated.label == "1.0-draft.2",
        updated.content == changed,
        updated.values("totalValue").value == MetadataValue.Number(2400),
        updated.values("contractPartner").origin == ValueOrigin.System("crm"),
        details.document.title == "Renamed",
        details.versions == List(updated.copy(updatedAt = details.versions.head.updatedAt)),
        audit.exists(e => e.action == "document.draft-changed" && e.details.value.contains("ACME AG"))
      )
    },
    test("locked sections and computed fields of the template cannot be changed") {
      for
        f       <- fixture
        created <- create(f)
        (doc, _)      = created
        lockedChanged = content.copy(sections =
          content.sections.map(s => if s.locked then s.copy(blocks = Nil) else s)
        )
        locked <- as(f.tenant)(
          _.updateDraft(doc.id, DraftChange(None, initialValues, Some(lockedChanged)), author)
        ).either
        computed <- as(f.tenant)(
          _.updateDraft(doc.id, DraftChange(None, initialValues, Some(content.copy(computed = Nil))), author)
        ).either
        unknown <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(
              None,
              initialValues,
              Some(
                content.copy(sections =
                  content.sections :+ Section(
                    SectionId("extra"),
                    None,
                    locked = false,
                    List(Block.Paragraph(List(Inline.Placeholder("unknownField"))))
                  )
                )
              )
            ),
            author
          )
        ).either
        details <- as(f.tenant)(_.get(doc.id))
      yield assertTrue(
        locked.left.exists {
          case DocumentError.Invalid(p) =>
            p.exists(_.contains("'parties' was changed without a deviation request"))
          case _ => false
        },
        computed.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        unknown.left.exists {
          case DocumentError.Invalid(p) => p == List("unknown field unknownField")
          case _                        => false
        },
        details.versions.map(_.workingState) == List(1)
      )
    },
    test("a locked section changes with a deviation request; the deviation report is stored with the draft") {
      for
        f       <- fixture
        created <- create(f)
        (doc, _) = created
        without <- as(f.tenant)(
          _.updateDraft(doc.id, DraftChange(None, initialValues, Some(deviating)), author)
        ).either
        editable  <- as(f.tenant)(_.requestDeviation(doc.id, SectionId("terms"), "Why not", author)).either
        unknown   <- as(f.tenant)(_.requestDeviation(doc.id, SectionId("nope"), "Why not", author)).either
        noReason  <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "  ", author)).either
        requested <- as(f.tenant)(
          _.requestDeviation(doc.id, Parties, " The customer insists on affiliates ", author)
        )
        (request, afterRequest) = requested
        duplicate <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Again", author)).either
        updated   <- as(f.tenant)(
          _.updateDraft(doc.id, DraftChange(None, initialValues, Some(deviating)), author)
        )
        stored   <- as(f.tenant)(_.get(doc.id)).map(_.versions.head)
        requests <- as(f.tenant)(_.deviationRequests(updated.id))
        audit    <- ZIO.serviceWithZIO[Env](_.audit.get)
      yield assertTrue(
        without.left.exists {
          case DocumentError.Invalid(p) =>
            p.exists(_.contains("'parties' was changed without a deviation request"))
          case _ => false
        },
        editable.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        unknown.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        noReason.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        duplicate.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        request.reason == "The customer insists on affiliates",
        request.requestedBy == author,
        // A request alone does not deviate yet.
        afterRequest.deviations.isEmpty,
        !afterRequest.requiresExtraReview,
        updated.content == deviating,
        updated.deviations.deviations.map(d => (d.section, d.change)) ==
          List(Parties -> DeviationChange.TextInserted("and its affiliates")),
        updated.deviations.deviations.forall(_.reasons == List("The customer insists on affiliates")),
        updated.requiresExtraReview,
        stored.deviations == updated.deviations,
        requests == List(request),
        audit.exists(e =>
          e.action == "document.deviation-requested" && e.details.value.contains(request.id.toString) &&
            e.details.value.contains("affiliates")
        )
      )
    },
    test(
      "withdrawn requests leave unauthorized deviations that block submission; submission freezes requests"
    ) {
      for
        f       <- fixture
        created <- create(f, reviewValues)
        (doc, _) = created
        requested <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Affiliates", author))
        _ <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, reviewValues, Some(deviating)), author))
        withdrawn <- as(f.tenant)(_.withdrawDeviation(doc.id, requested._1.id, author))
        twice     <- as(f.tenant)(_.withdrawDeviation(doc.id, requested._1.id, author)).either
        missing   <- as(f.tenant)(_.withdrawDeviation(doc.id, UUID.randomUUID(), author)).either
        blocked   <- as(f.tenant)(_.submit(doc.id, author)).either
        again     <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Affiliates, really", author))
        submitted <- as(f.tenant)(_.submit(doc.id, author))
        late      <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Late", author)).either
        lateOut   <- as(f.tenant)(_.withdrawDeviation(doc.id, again._1.id, author)).either
        stored    <- as(f.tenant)(_.get(doc.id)).map(_.versions.head)
        requests  <- as(f.tenant)(_.deviationRequests(submitted.id))
        payload   <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(
            Db.sql(
              sql"""select payload::text from dms.outbox_event
                    where aggregate_id = ${doc.id.value} and event_type = 'document.submitted'"""
                .query[String]
                .run()
                .head
            )
          )
        )
        audit <- ZIO.serviceWithZIO[Env](_.audit.get)
      yield assertTrue(
        withdrawn.deviations.deviations.nonEmpty,
        withdrawn.deviations.deviations.forall(_.unauthorized),
        !withdrawn.requiresExtraReview,
        twice.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        missing.left.exists(_.isInstanceOf[DocumentError.NotFound]),
        blocked.left.exists {
          case DocumentError.Invalid(p) =>
            p.exists(_.contains("'parties' was changed without a deviation request"))
          case _ => false
        },
        submitted.status == InReview,
        submitted.requiresExtraReview,
        submitted.deviations.deviations.forall(_.reasons == List("Affiliates, really")),
        stored.deviations == submitted.deviations,
        late.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        lateOut.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        requests.map(r => (r.reason, r.active)) == List("Affiliates" -> false, "Affiliates, really" -> true),
        requests.head.withdrawnBy.contains(author),
        payload.replace(" ", "").contains("\"requiresExtraReview\":true"),
        audit.exists(e => e.action == "document.deviation-withdrawn" && e.details.value.contains("parties"))
      )
    },
    test("a submission without deviations does not require the extra review") {
      for
        f       <- fixture
        created <- create(f, reviewValues)
        (doc, _) = created
        // A request without a change of the section is no deviation.
        _         <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Maybe later", author))
        submitted <- as(f.tenant)(_.submit(doc.id, author))
      yield assertTrue(submitted.deviations.isEmpty, !submitted.requiresExtraReview)
    },
    test("a new version carries over the active deviation requests of the valid version") {
      for
        f       <- fixture
        created <- create(f, reviewValues)
        (doc, v1) = created
        requested <- as(f.tenant)(_.requestDeviation(doc.id, Parties, "Affiliates", author))
        _  <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, reviewValues, Some(deviating)), author))
        _  <- as(f.tenant)(_.submit(doc.id, author))
        _  <- as(f.tenant)(_.changeStatus(v1.id, ReviewPassed, UserId("reviewer-1")))
        _  <- as(f.tenant)(_.attachRendition(v1.id, Rendition("renditions/1.pdf", None, "e" * 64), author))
        _  <- as(f.tenant)(_.changeStatus(v1.id, Approve, approver))
        _  <- as(f.tenant)(_.changeStatus(v1.id, Publish, approver))
        v2 <- as(f.tenant)(_.newVersion(doc.id, ChangeKind.Major, None, author))
        carried  <- as(f.tenant)(_.deviationRequests(v2.id))
        original <- as(f.tenant)(_.deviationRequests(v1.id))
        // The carried-over request keeps permitting the deviation in the new draft.
        edited <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(
              None,
              reviewValues,
              Some(
                deviating.copy(sections =
                  deviating.sections.map(s => if s.locked then s else s.copy(blocks = Nil))
                )
              )
            ),
            author
          )
        )
      yield assertTrue(
        carried.map(r => (r.sectionId, r.reason, r.requestedBy, r.basedOn)) ==
          List((Parties, "Affiliates", author, Some(requested._1.id))),
        original == List(requested._1),
        v2.content == deviating,
        v2.requiresExtraReview,
        edited.requiresExtraReview
      )
    },
    test("submission is blocked while required fields are missing, then moves the draft to review") {
      for
        f       <- fixture
        created <- create(f, initialValues - "contractPartner")
        (doc, _) = created
        blocked   <- as(f.tenant)(_.submit(doc.id, author)).either
        _         <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, reviewValues, None), author))
        submitted <- as(f.tenant)(_.submit(doc.id, author))
        again     <- as(f.tenant)(_.submit(doc.id, author)).either
        edit      <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, reviewValues, None), author)).either
        events    <- outbox(doc.id)
      yield assertTrue(
        blocked.left.toOption.collect { case DocumentError.Incomplete(InReview, issues) =>
          issues.map(_.field).toSet
        } == Some(
          Set(
            "owner",
            "organizationalUnit",
            "validFrom",
            "confidentiality",
            "language",
            "retentionRule",
            "containsPersonalData",
            "contractPartner"
          )
        ),
        submitted.status == InReview,
        submitted.label == "1.0-draft.2",
        again.left.exists(_.isInstanceOf[DocumentError.Lifecycle]),
        edit.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        events == List("document.created", "document.submitted")
      )
    },
    test("approval freezes the version with a metadata snapshot; the author cannot approve") {
      for
        f       <- fixture
        created <- create(f, reviewValues)
        (doc, v) = created
        _           <- as(f.tenant)(_.submit(doc.id, author))
        _           <- as(f.tenant)(_.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
        noRendition <- as(f.tenant)(_.changeStatus(v.id, Approve, approver)).either
        _ <- as(f.tenant)(_.attachRendition(v.id, Rendition("renditions/1.pdf", None, "b" * 64), author))
        byAuthor  <- as(f.tenant)(_.changeStatus(v.id, Approve, author)).either
        approved  <- as(f.tenant)(_.changeStatus(v.id, Approve, approver))
        rendition <- as(f.tenant)(
          _.attachRendition(v.id, Rendition("renditions/2.pdf", None, "c" * 64), author)
        ).either
        stored <- as(f.tenant)(_.get(doc.id)).map(_.versions.head)
      yield assertTrue(
        noRendition.left.toOption.collect { case DocumentError.Incomplete(Approved, issues) =>
          issues.map(_.field)
        } == Some(List("renditionChecksum")),
        byAuthor.left.exists {
          case DocumentError.Lifecycle(LifecycleError.SegregationOfDutiesViolated(u)) => u == author
          case _                                                                      => false
        },
        approved.label == "1.0",
        stored.releasedBy.contains(approver),
        stored.metadataSnapshot.exists(_.values.get("status").contains(MetadataValue.Selection("Approved"))),
        stored.metadataSnapshot.exists(
          _.values.get("renditionChecksum").contains(MetadataValue.Text("b" * 64))
        ),
        stored.metadataSnapshot.exists(_.values.get("totalValue").contains(MetadataValue.Number(1200))),
        rendition.left.exists(_.isInstanceOf[DocumentError.Conflict])
      )
    },
    test(
      "approval is blocked while a computed field has no value because a field it reads is empty (DMS-83)"
    ) {
      for
        f       <- fixture
        created <- create(f, reviewValues - "termMonths")
        (doc, v) = created
        _ <- as(f.tenant)(_.submit(doc.id, author))
        _ <- as(f.tenant)(_.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
        _ <- as(f.tenant)(_.attachRendition(v.id, Rendition("renditions/1.pdf", None, "b" * 64), author))
        blocked <- as(f.tenant)(_.changeStatus(v.id, Approve, approver)).either
        stored  <- as(f.tenant)(_.get(doc.id)).map(_.versions.head)
      yield assertTrue(
        blocked.left.toOption.collect { case DocumentError.Incomplete(Approved, issues) =>
          issues.map(i => (i.field, i.kind))
        } == Some(List("termMonths" -> IssueKind.MissingRequired(Approved))),
        !stored.values.contains("totalValue"),
        stored.status == InApproval
      )
    },
    test(
      "a new version starts from the valid one; one draft at a time; publication supersedes the old version"
    ) {
      for
        f      <- fixture
        doc    <- published(f)
        minor  <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some(" "), author)).either
        v2     <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, None, author))
        second <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, None, author)).either
        _      <- as(f.tenant)(_.submit(doc, author))
        _      <- as(f.tenant)(_.changeStatus(v2.id, ReviewPassed, UserId("reviewer-1")))
        _  <- as(f.tenant)(_.attachRendition(v2.id, Rendition("renditions/2.pdf", None, "d" * 64), author))
        _  <- as(f.tenant)(_.changeStatus(v2.id, Approve, approver))
        _  <- as(f.tenant)(_.changeStatus(v2.id, Publish, approver))
        v3 <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("Typo in section 2"), author))
        details <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        minor.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        v2.label == "2.0-draft.1",
        v2.values("version").value == MetadataValue.Text("2.0"),
        v2.content == content,
        second.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        v3.label == "2.1-draft.1",
        v3.changeReason.contains("Typo in section 2"),
        details.versions.map(v => (v.label, v.status)) ==
          List(("1.0", Superseded), ("2.0", Published), ("2.1-draft.1", Draft))
      )
    },
    test(
      "values taken over from a business system cannot be entered or overwritten by hand; the draft saves without them"
    ) {
      val taken = Map(
        "contractPartner" -> FieldEntry(MetadataValue.Text("Nordlicht Logistik GmbH"), PartnerMaster),
        "partnerId"       -> FieldEntry(MetadataValue.Text("P-10001"), PartnerMaster)
      )
      val others                                           = initialValues - "contractPartner"
      def code(result: Either[DbError | DocumentError, ?]) =
        result.left.toOption.collect { case DocumentError.InvalidValues(issues) =>
          issues.map(i => i.field -> i.kind.code)
        }
      for
        f     <- fixture
        typed <- as(f.tenant)(
          _.create(NewDocument(f.templateId, 3, "Contract", initialValues), author)
        ).either
        wrong <- as(f.tenant)(
          _.create(
            NewDocument(
              f.templateId,
              3,
              "Contract",
              others + ("partnerId" -> FieldEntry(MetadataValue.Text("P-1"), ValueOrigin.System("crm")))
            ),
            author
          )
        ).either
        // Partner master down: the author saves the draft without the partner ...
        without <- as(f.tenant)(_.create(NewDocument(f.templateId, 3, "Contract", others), author))
        (doc, first) = without
        // ... and picks it later.
        picked <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, others ++ taken, None), author))
        echoed <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(
              None,
              others ++ taken ++ manual("contractPartner" -> MetadataValue.Text("Nordlicht Logistik GmbH")),
              None
            ),
            author
          )
        )
        overwritten <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(
              None,
              others ++ taken ++ manual("contractPartner" -> MetadataValue.Text("Someone else")),
              None
            ),
            author
          )
        ).either
        references <- as(f.tenant)(
          _.updateDraft(
            doc.id,
            DraftChange(None, others ++ taken, Some(partnerContent.copy(references = Nil))),
            author
          )
        ).either
        removed <- as(f.tenant)(_.updateDraft(doc.id, DraftChange(None, others, None), author))
        // Outside reference fields, too: a value from a business system is not overwritten by hand.
        crm <- as(f.tenant)(
          _.create(
            NewDocument(
              f.templateId,
              1,
              "Contract",
              others + ("contractPartner" -> FieldEntry(
                MetadataValue.Text("ACME AG"),
                ValueOrigin.System("crm")
              ))
            ),
            author
          )
        )
        crmOverwritten <- as(f.tenant)(
          _.updateDraft(
            crm._1.id,
            DraftChange(
              None,
              initialValues + ("contractPartner" -> FieldEntry(
                MetadataValue.Text("Other"),
                ValueOrigin.Manual
              )),
              None
            ),
            author
          )
        ).either
      yield assertTrue(
        code(typed) == Some(List("contractPartner" -> "not_editable")),
        code(wrong) == Some(List("partnerId" -> "not_editable")),
        first.values.get("contractPartner").isEmpty,
        picked.values("contractPartner") == taken("contractPartner"),
        picked.values("partnerId") == taken("partnerId"),
        echoed.values("contractPartner").origin == PartnerMaster,
        code(overwritten) == Some(List("contractPartner" -> "not_editable")),
        references.left.exists {
          case DocumentError.Invalid(p) =>
            p.exists(_.contains("reference fields are defined by the template"))
          case _ => false
        },
        removed.values.get("contractPartner").isEmpty,
        removed.values.get("partnerId").isEmpty,
        code(crmOverwritten) == Some(List("contractPartner" -> "not_editable"))
      )
    },
    test("tenants only see their own documents") {
      for
        f       <- fixture
        other   <- fixture
        created <- create(f)
        (doc, _) = created
        foreign <- as(other.tenant)(_.get(doc.id)).either
        submit  <- as(other.tenant)(_.submit(doc.id, author)).either
      yield assertTrue(
        foreign.left.exists(_.isInstanceOf[DocumentError.NotFound]),
        submit.left.exists(_.isInstanceOf[DocumentError.NotFound])
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

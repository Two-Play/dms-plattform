package dms.templates.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.DocumentStatus
import dms.documents.metadata.*
import dms.kernel.{TenantId, UserId}
import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** Templates in PostgreSQL (migration V8) against a Testcontainers database: persistence, row-level security,
  * immutability and the lifecycle with four-eyes release.
  */
object TemplateServiceSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, audit: Ref[List[AuditEvent]])

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

  private val classId = DocumentClassId.random()

  /** The profile of the test class: core fields plus contract fields. */
  private val contractProfile: MetadataProfile =
    def field(key: String, fieldType: FieldType, required: Boolean = false) = ProfileField(
      FieldDefinition(FieldKey.unsafe(key), 1, key, None, fieldType),
      if required then Requiredness.from(DocumentStatus.InApproval) else Set.empty,
      Some(ClassKey.unsafe("contract"))
    )
    MetadataProfile(
      classId,
      ClassKey.unsafe("contract"),
      1,
      List(ClassKey.unsafe("contract")),
      CoreFields.fields ++ List(
        field("clientName", FieldType.Text(), required = true),
        field("monthlyFee", FieldType.Amount()),
        field(
          "termType",
          FieldType.Selection(List(SelectOption("fixed", "Fixed term"), SelectOption("open", "Open-ended")))
        )
      )
    )

  private object Profiles extends ProfileSource:
    def profile(id: UUID, version: Int): IO[TemplateError, MetadataProfile] =
      if id == classId.value && version == 1 then ZIO.succeed(contractProfile)
      else ZIO.fail(TemplateError.NotFound(s"document class $id version $version does not exist"))

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
    yield Env(Db(app), Db(owner), events)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  /** A tenant with the contract class (the class tables have no writer here, so the owner inserts them). */
  private def newTenant: ZIO[Env, DbError, TenantId] =
    val id = TenantId.random()
    ZIO.serviceWithZIO[Env](e =>
      e.owner
        .transactSystem(Db.sql {
          val _        = sql"insert into dms.tenant (id, name) values (${id.value}, ${"Tenant"})".update.run()
          val classRow = UUID.randomUUID()
          val _        = sql"""insert into dms.document_class (id, tenant_id, key, created_by)
                        values ($classRow, ${id.value}, ${"contract"}, ${"admin"})""".update.run()
          val _ =
            sql"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
                        values (${id.value}, $classRow, 1, ${"Contract"}, ${"admin"})""".update.run()
          classRows.put(id, classRow)
        })
        .as(id)
    )

  /** The database id of the contract class per tenant; the profile stub answers for every tenant's class. */
  private val classRows = java.util.concurrent.ConcurrentHashMap[TenantId, UUID]()

  private val designer  = UserId("designer-1")
  private val designer2 = UserId("designer-2")
  private val designer3 = UserId("designer-3")

  private val content = TemplateContent(
    List(
      Section(
        SectionId("main"),
        Some("Contract"),
        locked = false,
        List(
          Paragraph(List(Text("Between "), Placeholder("clientName"))),
          Conditional(
            "termType == 'fixed'",
            List(Paragraph(List(Text("Fixed term")))),
            List(Paragraph(List(Text("Open-ended"))))
          ),
          Conditional(
            "monthlyFee.amount > 1000.0",
            List(Paragraph(List(Placeholder("monthlyFee", Some("amount")))))
          )
        )
      )
    )
  )

  private def draft(tenant: TenantId, c: TemplateContent = content, name: String = "Service contract") =
    // The service checks against the stub profile (by classId); the row references the tenant's class row.
    TemplateDraft(name, Some("Standard service contract"), classRows.get(tenant), 1, c)

  private def asTenant[A](tenant: TenantId)(body: DbTx ?=> A) =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(body)))

  /** The stub profile is keyed by `classId`; map each tenant's class row to it. */
  private val stubbed: ProfileSource = new ProfileSource:
    def profile(id: UUID, version: Int): IO[TemplateError, MetadataProfile] =
      if classRows.containsValue(id) then Profiles.profile(classId.value, version)
      else Profiles.profile(id, version)

  private def service: ZIO[Env, Nothing, TemplateService] =
    ZIO.serviceWith[Env](e => TemplateService(RecordingAuditTrail(e.audit), stubbed))

  private def run[A](tenant: TenantId)(body: TemplateService => IO[TemplateError, A]) =
    for
      s <- service
      e <- ZIO.service[Env]
      a <- e.app.transact(tenant)(body(s))
    yield a

  def spec = suite("TemplateService")(
    test("creating a template stores version 1 as a draft and records it in the audit trail") {
      for
        t        <- newTenant
        created  <- run(t)(_.create(draft(t), designer))
        versions <- run(t)(_.versions(created.templateId))
        events   <- ZIO.serviceWithZIO[Env](_.audit.get)
      yield assertTrue(
        created.version == 1,
        created.status == TemplateStatus.Draft,
        versions == List(created.copy(createdAt = versions.head.createdAt)),
        versions.head.content == content,
        events.exists(e => e.action == "template.created" && e.target.id.contains(created.templateId.value))
      )
    },
    test("invalid expressions are rejected on save with their position") {
      for
        t <- newTenant
        broken = content.copy(sections =
          content.sections.map(s => s.copy(blocks = s.blocks :+ Conditional("termType == ", Nil)))
        )
        error <- run(t)(_.create(draft(t, broken), designer)).flip
      yield assertTrue(error match
        case TemplateError.InvalidContent(List(issue)) =>
          issue.location == Location(Some(SectionId("main")), "/blocks/3", "condition", 1, 13)
        case _ => false)
    },
    test("only drafts can be edited; editors are recorded") {
      for
        t       <- newTenant
        created <- run(t)(_.create(draft(t), designer))
        id = created.templateId
        edited   <- run(t)(_.updateDraft(id, 1, draft(t, name = "Renamed"), designer2))
        _        <- run(t)(_.changeStatus(id, 1, TemplateStatus.Testing, designer))
        rejected <- run(t)(_.updateDraft(id, 1, draft(t), designer)).flip
      yield assertTrue(
        edited.name == "Renamed",
        edited.editedBy == List(designer2),
        edited.contributors == Set(designer, designer2),
        rejected == TemplateError.Lifecycle(TemplateLifecycleError.NotEditable(TemplateStatus.Testing))
      )
    },
    test("release follows the four-eyes principle; creator and editors cannot release") {
      for
        t       <- newTenant
        created <- run(t)(_.create(draft(t), designer))
        id = created.templateId
        _           <- run(t)(_.updateDraft(id, 1, draft(t), designer2))
        _           <- run(t)(_.changeStatus(id, 1, TemplateStatus.Testing, designer))
        byCreator   <- run(t)(_.changeStatus(id, 1, TemplateStatus.Released, designer)).flip
        byEditor    <- run(t)(_.changeStatus(id, 1, TemplateStatus.Released, designer2)).flip
        released    <- run(t)(_.changeStatus(id, 1, TemplateStatus.Released, designer3))
        backToDraft <- run(t)(_.changeStatus(id, 1, TemplateStatus.Draft, designer3)).flip
      yield assertTrue(
        byCreator == TemplateError.Lifecycle(TemplateLifecycleError.SegregationOfDutiesViolated(designer)),
        byEditor == TemplateError.Lifecycle(TemplateLifecycleError.SegregationOfDutiesViolated(designer2)),
        released.status == TemplateStatus.Released,
        released.releasedBy.contains(designer3),
        released.releasedAt.isDefined,
        backToDraft == TemplateError.Lifecycle(
          TemplateLifecycleError.TransitionNotAllowed(TemplateStatus.Released, TemplateStatus.Draft)
        )
      )
    },
    test("the database rejects a release by the creator, changes to released versions and deletes") {
      for
        t       <- newTenant
        created <- run(t)(_.create(draft(t), designer))
        id = created.templateId.value
        _           <- run(t)(_.changeStatus(created.templateId, 1, TemplateStatus.Testing, designer))
        selfRelease <- asTenant(t)(
          sql"""update dms.template_version set status = 'Released', released_by = ${designer.value},
                released_at = now() where template_id = $id""".update.run()
        ).flip
        skipTesting <- asTenant(t)(
          sql"update dms.template_version set status = 'Archived' where template_id = $id".update.run()
        ).flip
        _             <- run(t)(_.changeStatus(created.templateId, 1, TemplateStatus.Released, designer2))
        contentChange <- asTenant(t)(
          sql"update dms.template_version set name = ${"Changed"} where template_id = $id".update.run()
        ).flip
        delete <- asTenant(t)(sql"delete from dms.template_version where template_id = $id".update.run()).flip
      yield assertTrue(
        selfRelease.isInstanceOf[DbError.Other], // check constraint (23514)
        skipTesting.isInstanceOf[DbError.AppendOnlyViolation],
        contentChange.isInstanceOf[DbError.AppendOnlyViolation],
        delete.isInstanceOf[DbError.PermissionDenied]
      )
    },
    test("a template has one open version; releasing a new version does not deprecate the previous one") {
      for
        t       <- newTenant
        created <- run(t)(_.create(draft(t), designer))
        id = created.templateId
        whileOpen <- run(t)(_.newVersion(id, draft(t), designer)).flip
        _         <- run(t)(_.changeStatus(id, 1, TemplateStatus.Testing, designer))
        _         <- run(t)(_.changeStatus(id, 1, TemplateStatus.Released, designer2))
        v2        <- run(t)(_.newVersion(id, draft(t, name = "Service contract 2"), designer))
        _         <- run(t)(_.changeStatus(id, 2, TemplateStatus.Testing, designer))
        catalogV1 <- run(t)(_.catalog)
        _         <- run(t)(_.changeStatus(id, 2, TemplateStatus.Released, designer2))
        both      <- run(t)(_.catalog)
        _         <- run(t)(_.changeStatus(id, 1, TemplateStatus.Deprecated, designer2))
        _         <- run(t)(_.changeStatus(id, 1, TemplateStatus.Archived, designer2))
        catalog   <- run(t)(_.catalog)
        latest    <- run(t)(_.list)
      yield assertTrue(
        whileOpen.isInstanceOf[TemplateError.Conflict],
        v2.version == 2,
        catalogV1.map(_.version) == List(1),
        both.map(_.version) == List(1, 2),
        catalog.map(_.version) == List(2),
        latest.map(_.version) == List(2)
      )
    },
    test("templates are isolated per tenant") {
      for
        a       <- newTenant
        b       <- newTenant
        created <- run(a)(_.create(draft(a), designer))
        other   <- run(b)(_.versions(created.templateId)).flip
        list    <- run(b)(_.list)
      yield assertTrue(other.isInstanceOf[TemplateError.NotFound], list.isEmpty)
    },
    test("test mode resolves generated sample data covering every branch, or the given values") {
      for
        t          <- newTenant
        created    <- run(t)(_.create(draft(t), designer))
        generated  <- run(t)(_.test(created.templateId, 1, None, Language.De))
        withValues <- run(t)(
          _.test(created.templateId, 1, Some(Map("clientName" -> FieldValue.Text("ACME"))), Language.En)
        )
        wrong <- run(t)(
          _.test(created.templateId, 1, Some(Map("clientName" -> FieldValue.YesNo(true))), Language.En)
        ).flip
      yield assertTrue(
        generated.coverage.size == 2,
        generated.coverage.forall(_.complete),
        generated.cases.forall(_._2.isRight),
        withValues.cases.size == 1,
        withValues.cases.head._2.exists(_.content.plainText.contains("Between ACME")),
        wrong.isInstanceOf[TemplateError.Invalid]
      )
    }
  ).provideLayerShared(env) @@ TestAspect.sequential

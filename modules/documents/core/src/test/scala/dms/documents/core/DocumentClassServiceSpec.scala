package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.DocumentStatus.*
import dms.documents.metadata.*
import dms.kernel.{TenantId, UserId}
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import javax.sql.DataSource

/** Document classes and field definitions in PostgreSQL (migration V7), against a Testcontainers database. */
object DocumentClassServiceSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, service: DocumentClassService, audit: Ref[List[AuditEvent]])

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  /** Collects recorded events; the hash chain itself is covered in audit-core. */
  private final class RecordingAuditTrail(events: Ref[List[AuditEvent]]) extends AuditTrail:
    def record(event: AuditEvent): IO[DbError, AuditRecordId] =
      events.update(_ :+ event).as(AuditRecordId(0L))
    def verify(tenant: TenantId): IO[DbError, VerificationReport] =
      ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None))

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
    yield Env(Db(app), Db(owner), DocumentClassService(RecordingAuditTrail(events)), events)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def newTenant(owner: Db): IO[DbError, TenantId] =
    val id = TenantId.random()
    owner
      .transactSystem(
        Db.sql(sql"insert into dms.tenant (id, name) values (${id.value}, ${"Tenant"})".update.run())
      )
      .as(id)

  private val admin = UserId("admin-1")

  private val partnerDraft = FieldDraft("Contract partner", None, FieldType.Text(maxLength = Some(100)))
  private val noticeDraft  = FieldDraft(
    "Notice period",
    None,
    FieldType.Selection(List(SelectOption("1m", "One month"), SelectOption("6m", "Six months")))
  )

  private def binding(
      key: String,
      version: Int = 1,
      requiredIn: Set[dms.documents.DocumentStatus] = Set.empty
  ) =
    FieldBinding(FieldKey.unsafe(key), version, requiredIn)

  /** Runs `body` for `tenant` with the service of the environment. */
  private def as[A](tenant: TenantId)(body: DocumentClassService => IO[MetadataError, A]) =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(body(e.service)))

  def spec = suite("DocumentClassService")(
    test("a new field version is added and the previous version is kept") {
      for
        t  <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _  <- as(t)(_.createField("noticePeriod", noticeDraft, admin))
        v2 <- as(t)(
          _.newFieldVersion(
            "noticePeriod",
            noticeDraft.copy(fieldType = FieldType.Selection(List(SelectOption("1m", "One month")))),
            admin
          )
        )
        versions <- as(t)(_.fieldVersions("noticePeriod"))
        latest   <- as(t)(_.listFields)
      yield assertTrue(
        v2.version == 2,
        versions.map(_.version) == List(1, 2),
        versions.head.fieldType == noticeDraft.fieldType,
        versions(1).fieldType == FieldType.Selection(
          List(SelectOption("1m", "One month"), SelectOption("6m", "Six months", active = false))
        ),
        latest.filter(_.origin == FieldOrigin.Tenant).map(d => (d.key.value, d.version)) == List(
          ("noticePeriod", 2)
        ),
        latest.count(_.origin != FieldOrigin.Tenant) == CoreFields.fields.size
      )
    },
    test("core field keys are reserved and duplicate keys conflict") {
      for
        t         <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        core      <- as(t)(_.createField("title", partnerDraft, admin)).either
        _         <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        duplicate <- as(t)(_.createField("contractPartner", partnerDraft, admin)).either
        coreEdit  <- as(t)(_.newFieldVersion("title", partnerDraft, admin)).either
      yield assertTrue(
        core.left.exists(_.isInstanceOf[MetadataError.Invalid]),
        duplicate.left.exists(_.isInstanceOf[MetadataError.Conflict]),
        coreEdit.left.exists(_.isInstanceOf[MetadataError.Invalid])
      )
    },
    test("a child class inherits the profile of the pinned parent version") {
      for
        t        <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _        <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        _        <- as(t)(_.createField("noticePeriod", noticeDraft, admin))
        contract <- as(t)(
          _.createClass(
            "contract",
            None,
            ClassDraft(
              "Contract",
              None,
              List(binding("contractPartner", requiredIn = Requiredness.from(InReview)))
            ),
            admin
          )
        )
        service <- as(t)(
          _.createClass(
            "service-contract",
            Some(contract.classId),
            ClassDraft("Service contract", None, List(binding("noticePeriod"))),
            admin
          )
        )
        profile <- as(t)(_.profile(service.classId))
      yield assertTrue(
        service.parent.contains(contract.ref),
        profile.classPath.map(_.value) == List("contract", "service-contract"),
        profile
          .field("contractPartner")
          .exists(f => f.isRequiredIn(InReview) && f.definedBy.exists(_.value == "contract")),
        profile.field("noticePeriod").exists(_.definedBy.exists(_.value == "service-contract")),
        profile.field("title").isDefined
      )
    },
    test("new versions keep old versions; children change only with their own new version") {
      for
        t        <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _        <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        _        <- as(t)(_.createField("noticePeriod", noticeDraft, admin))
        contract <- as(t)(_.createClass("contract", None, ClassDraft("Contract", None, Nil), admin))
        child    <- as(t)(_.createClass("nda", Some(contract.classId), ClassDraft("NDA", None, Nil), admin))
        _        <- as(t)(
          _.newClassVersion(
            contract.classId,
            ClassDraft("Contract", None, List(binding("contractPartner"))),
            admin
          )
        )
        childStill <- as(t)(_.profile(child.classId))
        child2     <- as(t)(
          _.newClassVersion(child.classId, ClassDraft("NDA", None, List(binding("noticePeriod"))), admin)
        )
        childNow <- as(t)(_.profile(child.classId))
        childOld <- as(t)(_.profile(child.classId, Some(1)))
        versions <- as(t)(_.classVersions(child.classId))
        latest   <- as(t)(_.listClasses)
      yield assertTrue(
        childStill.field("contractPartner").isEmpty,
        child2.parent.contains(ClassRef(contract.classId, 2)),
        childNow.field("contractPartner").isDefined,
        childNow.field("noticePeriod").isDefined,
        childOld.field("contractPartner").isEmpty && childOld.version == 1,
        versions.map(_.version) == List(1, 2),
        latest.map(c => (c.key.value, c.version)).sorted == List(("contract", 2), ("nda", 2))
      )
    },
    test("an inherited required field cannot be made optional and unknown fields are rejected") {
      for
        t        <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _        <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        contract <- as(t)(
          _.createClass(
            "contract",
            None,
            ClassDraft(
              "Contract",
              None,
              List(binding("contractPartner", requiredIn = Requiredness.from(InReview)))
            ),
            admin
          )
        )
        weakened <- as(t)(
          _.createClass(
            "loose",
            Some(contract.classId),
            ClassDraft("Loose", None, List(binding("contractPartner"))),
            admin
          )
        ).either
        unknown <- as(t)(
          _.createClass("other", None, ClassDraft("Other", None, List(binding("doesNotExist"))), admin)
        ).either
        classes <- as(t)(_.listClasses)
      yield assertTrue(
        weakened.left.exists {
          case MetadataError.Invalid(problems) => problems.exists(_.contains("cannot be made optional"))
          case _                               => false
        },
        unknown.left.exists(_.isInstanceOf[MetadataError.Invalid]),
        classes.map(_.key.value) == List("contract")
      )
    },
    test("tenants only see their own classes and field definitions") {
      for
        a        <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        b        <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _        <- as(a)(_.createField("contractPartner", partnerDraft, admin))
        contract <- as(a)(
          _.createClass(
            "contract",
            None,
            ClassDraft("Contract", None, List(binding("contractPartner"))),
            admin
          )
        )
        fieldsB  <- as(b)(_.listFields)
        classesB <- as(b)(_.listClasses)
        profileB <- as(b)(_.profile(contract.classId)).either
        childB   <- as(b)(
          _.createClass("sub", Some(contract.classId), ClassDraft("Sub", None, Nil), admin)
        ).either
        ownB <- as(b)(_.createField("contractPartner", partnerDraft, admin)).either
      yield assertTrue(
        fieldsB.forall(_.origin != FieldOrigin.Tenant),
        classesB.isEmpty,
        profileB.left.exists(_.isInstanceOf[MetadataError.NotFound]),
        childB.left.exists(_.isInstanceOf[MetadataError.NotFound]),
        ownB.isRight
      )
    },
    test("stored versions are append-only") {
      for
        e         <- ZIO.service[Env]
        t         <- newTenant(e.owner)
        _         <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        appUpdate <- e.app
          .transact(t)(Db.sql(sql"update dms.field_definition set label = 'x'".update.run()))
          .either
        ownerDelete <- e.owner
          .transactSystem(Db.sql(sql"delete from dms.field_definition".update.run()))
          .either
      yield assertTrue(
        appUpdate.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        ownerDelete.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation])
      )
    },
    test("configuration changes are recorded in the audit trail") {
      for
        e      <- ZIO.service[Env]
        t      <- newTenant(e.owner)
        _      <- e.audit.set(Nil)
        _      <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        cls    <- as(t)(_.createClass("contract", None, ClassDraft("Contract", None, Nil), admin))
        _      <- as(t)(_.newClassVersion(cls.classId, ClassDraft("Contract (v2)", None, Nil), admin))
        events <- e.audit.get
      yield assertTrue(
        events.map(_.action) == List(
          "field-definition.created",
          "document-class.created",
          "document-class.version-created"
        ),
        events.forall(_.actor.id == admin.value),
        events.last.target == AuditObject("document-class", Some(cls.classId.value), Some("2"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

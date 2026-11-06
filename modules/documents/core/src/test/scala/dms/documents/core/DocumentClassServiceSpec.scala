package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.DocumentStatus.*
import dms.documents.ReviewPolicy
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
    test("a format from before RE2 refuses new class versions, while old versions keep working (DMS-160)") {
      val key    = FieldKey.unsafe("legacyCode")
      val legacy = FieldDefinition(key, 1, "Code", None, FieldType.Text(pattern = Some("(?=A)[A-Z]+")))
      val id     = DocumentClassId.random()
      val old    = DocumentClassVersion(
        id,
        ClassKey.parse("legacy-old").toOption.get,
        1,
        None,
        "Old",
        None,
        List(binding("legacyCode")),
        ReviewPolicy.default
      )
      // what is stored from before the rule; the service would refuse it now
      def stored(t: TenantId) =
        ZIO.serviceWithZIO[Env](
          _.app.transact(t)(Db.sql {
            val _ = MetadataStore.insertField(legacy, admin.value)
            val _ = MetadataStore.insertClass(id, old.key, None, admin.value)
            MetadataStore.insertClassVersion(old, admin.value)
          })
        )
      def issues(t: TenantId, value: String) =
        as(t)(
          _.validate(
            id,
            Some(1),
            Map("title" -> MetadataValue.Text("Title"), "legacyCode" -> MetadataValue.Text(value)),
            Draft,
            false
          )
        ).map(_._2.map(_.kind.code))
      for
        t          <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _          <- stored(t)
        refusedNew <- as(t)(
          _.createClass("legacy-new", None, ClassDraft("New", None, List(binding("legacyCode"))), admin)
        ).either
        fits       <- issues(t, "ABC")
        misfits    <- issues(t, "BCD")
        refusedOld <- as(t)(
          _.newClassVersion(id, ClassDraft("Old", None, List(binding("legacyCode"))), admin)
        ).either
        replaced <- as(t)(
          _.newFieldVersion(
            "legacyCode",
            FieldDraft("Code", None, FieldType.Text(pattern = Some("A[A-Z]*"))),
            admin
          )
        )
        next <- as(t)(
          _.newClassVersion(id, ClassDraft("Old", None, List(binding("legacyCode", replaced.version))), admin)
        )
      yield assertTrue(
        refusedNew.left.exists {
          case MetadataError.Invalid(p) =>
            p.exists(m => m.contains("legacyCode") && m.contains("not supported"))
          case _ => false
        },
        fits.isEmpty,
        misfits == List("pattern_mismatch"),
        refusedOld.left.exists(_.isInstanceOf[MetadataError.Invalid]),
        next.version == 2
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
    test("changing requiredness creates a new profile version; the old version keeps its rules") {
      for
        t   <- ZIO.serviceWithZIO[Env](e => newTenant(e.owner))
        _   <- as(t)(_.createField("contractPartner", partnerDraft, admin))
        cls <- as(t)(
          _.createClass(
            "contract",
            None,
            ClassDraft("Contract", None, List(binding("contractPartner"))),
            admin
          )
        )
        v2 <- as(t)(
          _.newClassVersion(
            cls.classId,
            ClassDraft(
              "Contract",
              None,
              List(binding("contractPartner", requiredIn = Requiredness.from(InReview)))
            ),
            admin
          )
        )
        values = Map("title" -> MetadataValue.Text("Framework agreement"))
        onOld <- as(t)(_.validate(cls.classId, Some(1), values, InReview, includeSystemFields = false))
        onNew <- as(t)(_.validate(cls.classId, None, values, InReview, includeSystemFields = false))
        draft <- as(t)(_.validate(cls.classId, None, values, Draft, includeSystemFields = false))
      yield assertTrue(
        v2.version == 2,
        onOld._1.version == 1,
        !onOld._2.exists(_.field == "contractPartner"),
        onNew._1.version == 2,
        onNew._2.exists(i => i.field == "contractPartner" && i.kind == IssueKind.MissingRequired(InReview)),
        draft._2.isEmpty
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
    },
    test("review settings are stored per class version, kept by later versions and validated") {
      val yearly = ReviewPolicy(Some(12), 30)
      for
        e       <- ZIO.service[Env]
        t       <- newTenant(e.owner)
        _       <- e.audit.set(Nil)
        created <- as(t)(
          _.createClass("policy", None, ClassDraft("Policy", None, Nil, review = Some(yearly)), admin)
        )
        kept    <- as(t)(_.newClassVersion(created.classId, ClassDraft("Policy (v2)", None, Nil), admin))
        changed <- as(t)(
          _.newClassVersion(
            created.classId,
            ClassDraft("Policy (v3)", None, Nil, review = Some(ReviewPolicy())),
            admin
          )
        )
        current  <- as(t)(_.reviewPolicy(created.classId))
        versions <- as(t)(_.classVersions(created.classId))
        invalid  <- as(t)(
          _.newClassVersion(
            created.classId,
            ClassDraft("Policy (v4)", None, Nil, review = Some(ReviewPolicy(Some(0), 400))),
            admin
          )
        ).either
        plain  <- as(t)(_.createClass("form", None, ClassDraft("Form", None, Nil), admin))
        events <- e.audit.get
      yield assertTrue(
        created.review == yearly,
        kept.review == yearly,
        changed.review == ReviewPolicy(None, 60),
        current == ReviewPolicy(None, 60),
        versions.map(_.review) == List(yearly, yearly, ReviewPolicy()),
        invalid.left.exists {
          case MetadataError.Invalid(problems) => problems.size == 2
          case _                               => false
        },
        plain.review == ReviewPolicy.default,
        events.head.details.value
          .replace(" ", "")
          .contains("""{"intervalMonths":12,"leadDays":30,"inherited":false}""")
      )
    },
    test("a subclass inherits the review settings of the nearest ancestor that has its own") {
      val yearly = ReviewPolicy(Some(12), 30)
      for
        e    <- ZIO.service[Env]
        t    <- newTenant(e.owner)
        root <- as(t)(
          _.createClass("root", None, ClassDraft("Root", None, Nil, review = Some(yearly)), admin)
        )
        child <- as(t)(_.createClass("child", Some(root.classId), ClassDraft("Child", None, Nil), admin))
        leaf  <- as(t)(_.createClass("leaf", Some(child.classId), ClassDraft("Leaf", None, Nil), admin))
        own   <- as(t)(
          _.createClass(
            "own",
            Some(child.classId),
            ClassDraft("Own", None, Nil, review = Some(ReviewPolicy(Some(6), 10))),
            admin
          )
        )
        optOut <- as(t)(
          _.createClass(
            "none",
            Some(root.classId),
            ClassDraft("None", None, Nil, review = Some(ReviewPolicy())),
            admin
          )
        )
        before <- as(t)(_.reviewPolicies)
        // The ancestors count with their latest version, not with the pinned parent version.
        _ <- as(t)(
          _.newClassVersion(
            root.classId,
            ClassDraft("Root", None, Nil, review = Some(ReviewPolicy(Some(24), 45))),
            admin
          )
        )
        changed <- as(t)(_.reviewPolicy(leaf.classId))
        kept    <- as(t)(_.newClassVersion(child.classId, ClassDraft("Child (v2)", None, Nil), admin))
        back    <- as(t)(
          _.newClassVersion(
            own.classId,
            ClassDraft("Own (v2)", None, Nil, review = Some(ReviewPolicy.inherit)),
            admin
          )
        )
        again <- as(t)(_.reviewPolicy(own.classId))
      yield assertTrue(
        child.review == ReviewPolicy.inherit,
        before(child.classId) == yearly,
        before(leaf.classId) == yearly,
        before(own.classId) == ReviewPolicy(Some(6), 10),
        before(optOut.classId) == ReviewPolicy(),
        changed == ReviewPolicy(Some(24), 45),
        kept.review == ReviewPolicy.inherit,
        back.review == ReviewPolicy.inherit,
        again == ReviewPolicy(Some(24), 45)
      )
    },
    test("a root class cannot inherit, and inherited settings have no values of their own") {
      for
        e    <- ZIO.service[Env]
        t    <- newTenant(e.owner)
        root <- as(t)(
          _.createClass(
            "root",
            None,
            ClassDraft("Root", None, Nil, review = Some(ReviewPolicy.inherit)),
            admin
          )
        ).either
        plain <- as(t)(_.createClass("plain", None, ClassDraft("Plain", None, Nil), admin))
        mixed <- as(t)(
          _.createClass(
            "mixed",
            Some(plain.classId),
            ClassDraft("Mixed", None, Nil, review = Some(ReviewPolicy(Some(12), inherited = true))),
            admin
          )
        ).either
        stored <- e.app
          .transact(t)(
            Db.sql(
              sql"""insert into dms.document_class_version
                      (tenant_id, class_id, version, label, review_inherited, created_by)
                    values (${t.value}, ${plain.classId.value}, 2, 'Plain', true, 'admin')""".update.run()
            )
          )
          .either
      yield assertTrue(
        root.left.exists(_.isInstanceOf[MetadataError.Invalid]),
        mixed.left.exists(_.isInstanceOf[MetadataError.Invalid]),
        stored.isLeft
      )
    },
    test("a subclass version from before V46 inherits only if it never set anything") {
      def legacy(t: TenantId, id: DocumentClassId, interval: Option[Int], lead: Int) =
        ZIO.serviceWithZIO[Env](
          _.app.transact(t)(
            Db.sql(
              sql"""insert into dms.document_class_version
                      (tenant_id, class_id, version, parent_version, label, review_interval_months,
                       review_lead_days, created_by)
                    values (${t.value}, ${id.value}, 2, 1, 'Legacy', $interval, $lead, 'admin')""".update
                .run()
            )
          )
        )
      for
        e    <- ZIO.service[Env]
        t    <- newTenant(e.owner)
        root <- as(t)(
          _.createClass(
            "root",
            None,
            ClassDraft("Root", None, Nil, review = Some(ReviewPolicy(Some(12), 30))),
            admin
          )
        )
        unset    <- as(t)(_.createClass("unset", Some(root.classId), ClassDraft("Unset", None, Nil), admin))
        lead     <- as(t)(_.createClass("lead", Some(root.classId), ClassDraft("Lead", None, Nil), admin))
        interval <- as(t)(_.createClass("own", Some(root.classId), ClassDraft("Own", None, Nil), admin))
        _        <- legacy(t, unset.classId, None, 60)
        _        <- legacy(t, lead.classId, None, 20)
        _        <- legacy(t, interval.classId, Some(6), 60)
        policies <- as(t)(_.reviewPolicies)
        latest   <- as(t)(_.classVersions(unset.classId))
      yield assertTrue(
        policies(unset.classId) == ReviewPolicy(Some(12), 30),
        policies(lead.classId) == ReviewPolicy(None, 20),
        policies(interval.classId) == ReviewPolicy(Some(6), 60),
        latest.last.review == ReviewPolicy.inherit
      )
    },
    test(
      "a change of the interval in force asks for a recalculation once, in the transaction of the change"
    ) {
      def jobs(owner: Db, t: TenantId) =
        owner.transactSystem(
          Db.sql(
            sql"""select count(*) from dms.job
                  where tenant_id = ${t.value} and kind = ${ReviewJobs.RecalculationKind}"""
              .query[Long]
              .run()
              .head
          )
        )
      def change(id: DocumentClassId, review: ReviewPolicy) =
        (t: TenantId) =>
          as(t)(_.newClassVersion(id, ClassDraft("Class", None, Nil, review = Some(review)), admin))
      for
        e    <- ZIO.service[Env]
        t    <- newTenant(e.owner)
        _    <- e.audit.set(Nil)
        root <- as(t)(
          _.createClass(
            "root",
            None,
            ClassDraft("Root", None, Nil, review = Some(ReviewPolicy(Some(12), 30))),
            admin
          )
        )
        child <- as(t)(_.createClass("child", Some(root.classId), ClassDraft("Child", None, Nil), admin))
        none  <- jobs(e.owner, t)
        _     <- change(root.classId, ReviewPolicy(Some(12), 20))(t)
        lead  <- jobs(e.owner, t)
        _     <- change(root.classId, ReviewPolicy(Some(24), 20))(t)
        one   <- jobs(e.owner, t)
        // The child's own value equals the inherited one: nothing changes in force.
        _       <- change(child.classId, ReviewPolicy(Some(24), 60))(t)
        same    <- jobs(e.owner, t)
        _       <- change(child.classId, ReviewPolicy.inherit)(t)
        same2   <- jobs(e.owner, t)
        _       <- change(child.classId, ReviewPolicy(Some(6), 60))(t)
        changed <- jobs(e.owner, t)
        events  <- e.audit.get
      yield assertTrue(
        none == 0L,
        lead == 0L,
        one == 1L,
        same == 1L,
        same2 == 1L,
        changed == 2L,
        events.count(_.action == "document-class.review-interval-changed") == 2
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

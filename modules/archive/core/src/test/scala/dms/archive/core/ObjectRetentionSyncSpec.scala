package dms.archive.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.{GenericContainer, PostgreSQLContainer}
import dms.archive.*
import dms.audit.*
import dms.db.queue.ClaimedJob
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.{DocumentId, TenantId}
import dms.storage.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID
import javax.sql.DataSource

/** Object Lock retention of the objects of approved versions (DMS-93, migration V36, ADR 0055): the retention
  * job sets it on rendition, original and attachment files from the retention rule, records and audits it,
  * never shortens it, and composes with the object legal hold. Against PostgreSQL and SeaweedFS (which
  * implements S3 Object Lock, ADR 0016) started by Testcontainers.
  */
object ObjectRetentionSyncSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, audit: Ref[List[AuditEvent]], objects: ObjectStore)

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

  /** Same image as compose.yaml. */
  private val seaweedImage =
    "chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"

  private val objectStore: ZIO[Scope, Throwable, ObjectStore] =
    for
      c <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = GenericContainer(
          dockerImage = seaweedImage,
          exposedPorts = Seq(8333),
          env = Map("AWS_ACCESS_KEY_ID" -> "dms-test", "AWS_SECRET_ACCESS_KEY" -> "dms-test-secret"),
          command = Seq("mini", "-dir=/data"),
          waitStrategy = Wait.forLogMessage(".*All enabled components are running.*", 1)
        )
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      config = StorageConfig(
        endpoint = s"http://${c.host}:${c.mappedPort(8333)}",
        region = "us-east-1",
        bucket = "dms-test",
        accessKey = "dms-test",
        secretKey = "dms-test-secret"
      )
      s3 <- ObjectStore.client.build.map(_.get[S3Client]).provideSome[Scope](ZLayer.succeed(config))
      _  <- ObjectStore.bucketSetup
        .provide(ZLayer.succeed(s3), ZLayer.succeed(config))
        .mapError(e => RuntimeException(s"bucket setup failed: $e"))
    yield S3ObjectStore(s3, config.bucket)

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
      _       <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app     <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 3))
      owner   <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
      events  <- Ref.make(List.empty[AuditEvent])
      objects <- objectStore
    yield Env(Db(app), Db(owner), events, objects)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def owner[A](f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(f)))

  private def app[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  /** A tenant with a released template, inserted by the schema owner. */
  private def tenant: ZIO[Env, Any, (TenantId, UUID, UUID)] =
    val tenant   = TenantId.random()
    val classId  = UUID.randomUUID()
    val template = UUID.randomUUID()
    owner {
      val _ = sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run()
      val _ = sql"""insert into dms.document_class (id, tenant_id, key, created_by)
                    values ($classId, ${tenant.value}, ${"contract"}, ${"admin"})""".update.run()
      val _ = sql"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
                    values (${tenant.value}, $classId, 1, ${"Contract"}, ${"admin"})""".update.run()
      val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                    values ($template, ${tenant.value}, ${"designer"})""".update.run()
      val _ = sql"""insert into dms.template_version
                      (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                       content, created_by, status_changed_by)
                    values (${tenant.value}, $template, 1, ${"Contract"}, $classId, 1, ${"Testing"},
                            ${Jsonb("{}")}, ${"designer"}, ${"designer"})""".update.run()
      sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
            where template_id = $template""".update.run()
    }.as((tenant, classId, template))

  /** A file document with a draft version `major`.0; returns the document and version id. */
  private def document(t: (TenantId, UUID, UUID)): ZIO[Env, Any, DocumentId] =
    val (tenant, classId, template) = t
    val doc                         = UUID.randomUUID()
    owner(
      sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                      template_version, kind)
            values ($doc, ${tenant.value}, $classId, 1, ${"Supply contract"}, ${"author-1"}, $template, 1,
                    ${"File"})""".update.run()
    ).as(DocumentId(doc))

  private def draft(t: (TenantId, UUID, UUID), doc: DocumentId, major: Int): ZIO[Env, Any, UUID] =
    val (tenant, _, template) = t
    val version               = UUID.randomUUID()
    owner(
      sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, class_version, template_id,
                                              template_version, content, created_by, status_changed_by)
            values ($version, ${tenant.value}, ${doc.value}, $major, 0, 1, $template, 1, ${Jsonb("{}")},
                    ${"author-1"}, ${"author-1"})""".update.run()
    ).as(version)

  /** Stores bytes and registers them as a file of the document (attachment storage, V26). */
  private def file(tenant: TenantId, doc: DocumentId, text: String): ZIO[Env, Any, (UUID, ObjectVersion)] =
    for
      e <- ZIO.service[Env]
      fileId = UUID.randomUUID()
      key    = s"attachments/${tenant.value}/${doc.value}/$fileId"
      put <- e.objects.put(key, ObjectContent.Bytes(Chunk.fromArray(text.getBytes(UTF_8))), "text/plain")
      _   <- app(tenant)(
        sql"""insert into dms.document_attachment_file
                (id, tenant_id, document_id, sha256, size_bytes, content_type, object_key, object_version,
                 scan_status, scanner, scanned_at, created_by)
              values ($fileId, dms.current_tenant(), ${doc.value}, ${put.sha256.hex}, ${text.length.toLong},
                      ${"text/plain"}, $key, ${put.version.versionId}, ${"NotScanned"}, ${"none"}, now(),
                      ${"author-1"})""".update.run()
      )
    yield (fileId, put.version)

  private def attach(tenant: TenantId, version: UUID, fileId: UUID, name: String) =
    app(tenant)(
      sql"""insert into dms.document_version_attachment (id, tenant_id, version_id, file_id, name, added_by)
            values (${UUID
          .randomUUID()}, dms.current_tenant(), $version, $fileId, $name, ${"author-1"})""".update
        .run()
    )

  private def original(tenant: TenantId, version: UUID, fileId: UUID) =
    app(tenant)(
      sql"""insert into dms.document_version_file (version_id, tenant_id, file_id, name, set_by)
            values ($version, dms.current_tenant(), $fileId, ${"contract.docx"}, ${"author-1"})""".update
        .run()
    )

  /** Approves the draft as the schema owner with triggers disabled (the approval flow is not under test
    * here), with a rendition stored in object storage and the retention rule in the metadata snapshot.
    */
  private def approve(
      tenant: TenantId,
      doc: DocumentId,
      version: UUID,
      rule: Option[String],
      at: Instant
  ): ZIO[Env, Any, ObjectVersion] =
    for
      e <- ZIO.service[Env]
      key = s"renditions/${tenant.value}/${doc.value}/$version.pdf"
      put <- e.objects.put(
        key,
        ObjectContent.Bytes(Chunk.fromArray(s"pdf $version".getBytes(UTF_8))),
        "application/pdf"
      )
      snapshot = rule.fold("""{"values":{},"takenAt":"2027-01-01T00:00:00Z"}""")(r =>
        s"""{"values":{"retentionRule":{"type":"Text","value":"$r"}},"takenAt":"2027-01-01T00:00:00Z"}"""
      )
      _ <- owner {
        val _ = sql"set local session_replication_role = replica".update.run()
        sql"""update dms.document_version
              set status = 'Approved', rendition_key = $key, rendition_version = ${put.version.versionId},
                  rendition_sha256 = ${put.sha256.hex}, metadata_snapshot = ${Jsonb(snapshot)},
                  released_by = ${"approver-1"}, released_at = ${OffsetDateTime.ofInstant(at, ZoneOffset.UTC)}
              where id = $version""".update.run()
      }
    yield put.version

  private def recorded(tenant: TenantId, doc: DocumentId) =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(ObjectRetentions.ofDocument(doc)))

  private def retained(policy: ObjectRetentionPolicy) =
    ZIO.serviceWith[Env](e => ObjectRetentionSync(e.app, e.objects, policy, RecordingAuditTrail(e.audit)))

  private def auditOf(action: String) =
    ZIO.serviceWithZIO[Env](_.audit.get.map(_.filter(_.action == action)))

  private val approvedAt = Instant.parse("2027-03-01T10:00:00Z")

  private val berlin = ZoneId.of("Europe/Berlin")

  private def years(n: Long, from: Instant = approvedAt) =
    from.atOffset(ZoneOffset.UTC).plusYears(n).toInstant

  /** Locked through the last day of the retention (tenant time zone, Europe/Berlin by default). */
  private def through(lastDay: String) = LocalDate.parse(lastDay).plusDays(1).atStartOfDay(berlin).toInstant

  /** A retention rule of the tenant (DMS-82, `dms.retention_rule`), as the records manager maintains it. */
  private def rule(
      tenant: TenantId,
      key: String,
      months: Int,
      trigger: String = "Approval",
      yearEnd: Boolean = false
  ) =
    app(tenant)(
      sql"""insert into dms.retention_rule (tenant_id, key, name, period_months, trigger_event, from_year_end,
                                            created_by, updated_by)
            values (dms.current_tenant(), $key, $key, $months, $trigger, $yearEnd, ${"records-1"},
                    ${"records-1"})""".update.run()
    )

  def spec = suite("Object Lock retention of approved versions")(
    test(
      "rendition, original and attachments are locked until approval plus the rule's years, recorded, audited"
    ) {
      for
        e         <- ZIO.service[Env]
        t         <- tenant
        d         <- document(t)
        v1        <- draft(t, d, 1)
        orig      <- file(t._1, d, "original")
        annex     <- file(t._1, d, "annex")
        _         <- original(t._1, v1, orig._1)
        _         <- attach(t._1, v1, annex._1, "Preisblatt.txt")
        _         <- rule(t._1, "contract-7y", 84)
        rendition <- approve(t._1, d, v1, Some("contract-7y"), approvedAt)
        sync      <- retained(ObjectRetentionPolicy.default)
        result    <- sync.lock(t._1, d, v1)
        heads     <- ZIO.foreach(List(rendition, orig._2, annex._2))(o =>
          e.objects.head(o.key, Some(o.versionId))
        )
        rows    <- recorded(t._1, d)
        deletes <- ZIO.foreach(List(rendition, orig._2, annex._2))(o =>
          e.objects.delete(o.key, o.versionId).either
        )
        audits <- auditOf("document.objects-retained")
        details = audits.lastOption.flatMap(_.details.value.fromJson[Json.Obj].toOption)
        locked <- ZIO.serviceWithZIO[Env](
          _.app.transact(t._1)(ObjectRetentions.isLocked(rendition.key, rendition.versionId, Instant.now()))
        )
      yield assertTrue(
        result.exists(r => r.locked.size == 3 && r.skipped.isEmpty && r.locked.forall(_.changed)),
        result.exists(_.target == Retention(RetentionMode.Governance, through("2034-03-01"))),
        heads.forall(_.retention.contains(Retention(RetentionMode.Governance, through("2034-03-01")))),
        rows.map(r => (r.objectKey, r.objectVersion)).toSet ==
          Set(rendition, orig._2, annex._2).map(o => (o.key, o.versionId)),
        rows.forall(r =>
          r.mode == RetentionMode.Governance && r.retainUntil == through("2034-03-01") && r.versionId == v1 &&
            r.retentionRule.contains("contract-7y")
        ),
        // Without the bypass permission a governance lock refuses the delete like a compliance lock.
        deletes.forall(_.left.exists(_.isInstanceOf[StorageError.Locked])),
        details.flatMap(_.get("retainUntil")).contains(Json.Str(through("2034-03-01").toString)),
        details.flatMap(_.get("objects")).collect { case Json.Arr(items) => items.size }.contains(3),
        locked
      )
    },
    test("repeating is a no-op; a later approval sharing a file extends its lock; nothing is shortened") {
      for
        e      <- ZIO.service[Env]
        t      <- tenant
        d      <- document(t)
        v1     <- draft(t, d, 1)
        shared <- file(t._1, d, "shared annex")
        _      <- attach(t._1, v1, shared._1, "Anlage.txt")
        _      <- rule(t._1, "contract-10y", 120)
        _      <- approve(t._1, d, v1, Some("contract-10y"), approvedAt)
        sync   <- retained(ObjectRetentionPolicy.default)
        first  <- sync.lock(t._1, d, v1)
        again  <- sync.lock(t._1, d, v1)
        v2     <- draft(t, d, 2)
        _      <- attach(t._1, v2, shared._1, "Anlage.txt")
        later = approvedAt.plus(400, ChronoUnit.DAYS)
        _        <- approve(t._1, d, v2, Some("contract-10y"), later)
        extended <- sync.lock(t._1, d, v2)
        // the first version's job again (at-least-once delivery): the later lock stays
        repeated <- sync.lock(t._1, d, v1)
        head     <- e.objects.head(shared._2.key, Some(shared._2.versionId))
        row      <- ZIO.serviceWithZIO[Env](
          _.app.transact(t._1)(ObjectRetentions.lockOf(shared._2.key, shared._2.versionId))
        )
        shorten <- app(t._1)(
          sql"""update dms.object_retention set retain_until = now()
                where object_key = ${shared._2.key}""".update.run()
        ).either
        removed <- app(t._1)(
          sql"delete from dms.object_retention where object_key = ${shared._2.key}".update.run()
        ).either
      yield assertTrue(
        first.exists(_.locked.forall(_.changed)),
        again.exists(r => r.locked.nonEmpty && r.locked.forall(!_.changed)),
        extended.exists(_.locked.exists(l => l.obj.key == shared._2.key && l.changed)),
        repeated.exists(_.locked.forall(!_.changed)),
        head.retention.exists(_.until == through("2038-04-04")),
        row.exists(r => r.retainUntil == through("2038-04-04") && r.versionId == v2),
        shorten.isLeft,
        removed.isLeft
      )
    },
    test(
      "compliance refuses even the governance bypass; a longer rule extends the lock; legal holds keep it"
    ) {
      for
        e  <- ZIO.service[Env]
        t  <- tenant
        d  <- document(t)
        v1 <- draft(t, d, 1)
        _  <- rule(t._1, "hr-file", 72)
        r  <- approve(t._1, d, v1, Some("hr-file"), approvedAt)
        policy = ObjectRetentionPolicy(true, RetentionMode.Compliance, 10)
        sync     <- retained(policy)
        _        <- sync.lock(t._1, d, v1)
        _        <- e.objects.setLegalHold(r.key, r.versionId, on = true)
        _        <- e.objects.setLegalHold(r.key, r.versionId, on = false)
        head     <- e.objects.head(r.key, Some(r.versionId))
        bypassed <- e.objects.delete(r.key, r.versionId, bypassGovernance = true).either
        // the records manager lengthens the rule: the change queues the retention job (trigger of migration V36)
        _ <- app(t._1)(
          sql"""update dms.retention_rule set period_months = 360, updated_at = now(), updated_by = ${"records-1"}
                where key = ${"hr-file"}""".update.run()
        )
        queued <- owner(
          sql"""select count(*) from dms.job where kind = ${ObjectRetentions.JobKind}
                and idempotency_key like ${"object-retention:" + v1 + ":rule:hr-file:%"}"""
            .query[Long]
            .run()
            .head
        )
        // a governance policy later does not weaken the compliance lock (an object keeps its first mode)
        weaker <- retained(ObjectRetentionPolicy.default)
        kept   <- weaker.lock(t._1, d, v1)
        after  <- e.objects.head(r.key, Some(r.versionId))
      yield assertTrue(
        head.retention.contains(Retention(RetentionMode.Compliance, through("2033-03-01"))),
        queued == 1L,
        !head.legalHold,
        bypassed.left.exists(_.isInstanceOf[StorageError.Locked]),
        kept.exists(_.locked.forall(l => l.retention.mode == RetentionMode.Compliance && l.changed)),
        after.retention.contains(Retention(RetentionMode.Compliance, through("2057-03-01")))
      )
    },
    test(
      "disabled Object Lock sets nothing; missing objects are skipped; drafts and bad payloads are refused"
    ) {
      for
        e        <- ZIO.service[Env]
        t        <- tenant
        d        <- document(t)
        v1       <- draft(t, d, 1)
        r        <- approve(t._1, d, v1, None, approvedAt)
        disabled <- retained(ObjectRetentionPolicy.default.copy(enabled = false))
        off      <- disabled.lock(t._1, d, v1)
        head     <- e.objects.head(r.key, Some(r.versionId))
        rows     <- recorded(t._1, d)
        // a version referencing an object that does not exist
        d2 <- document(t)
        v2 <- draft(t, d2, 1)
        _  <- approve(t._1, d2, v2, None, approvedAt)
        _  <- owner {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"update dms.document_version set rendition_version = ${"no-such-version"} where id = $v2".update
            .run()
        }
        sync    <- retained(ObjectRetentionPolicy.default)
        missing <- sync.lock(t._1, d2, v2)
        // a rule counted from the document's creation that ended long ago: nothing is locked
        d4 <- document(t)
        v4 <- draft(t, d4, 1)
        _  <- rule(t._1, "old-file", 12, trigger = "Creation")
        _  <- owner {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"update dms.document set created_at = '2000-01-01T00:00:00Z' where id = ${d4.value}".update.run()
        }
        _     <- approve(t._1, d4, v4, Some("old-file"), approvedAt)
        ended <- sync.lock(t._1, d4, v4)
        // a draft is not approved: nothing to lock
        d3       <- document(t)
        v3       <- draft(t, d3, 1)
        notReady <- sync.lock(t._1, d3, v3)
        viaJob   <- sync
          .handle(
            ClaimedJob(
              1L,
              Some(t._1),
              ObjectRetentions.JobKind,
              Jsonb(s"""{"documentId":"${d.value}","versionId":"$v1"}"""),
              1,
              10
            )
          )
          .either
        bad <- sync.handle(ClaimedJob(2L, Some(t._1), ObjectRetentions.JobKind, Jsonb("{}"), 1, 10)).either
        noTenant <- sync
          .handle(
            ClaimedJob(
              3L,
              None,
              ObjectRetentions.JobKind,
              Jsonb(s"""{"documentId":"${d.value}","versionId":"$v1"}"""),
              1,
              10
            )
          )
          .either
      yield assertTrue(
        off.isEmpty,
        head.retention.isEmpty,
        rows.isEmpty,
        missing.exists(r => r.locked.isEmpty && r.skipped.size == 1),
        ended.exists(r => r.locked.isEmpty && r.skipped.forall(_.reason.contains("retention ended"))),
        notReady.isEmpty,
        viaJob.isRight,
        bad.isLeft,
        noTenant.isLeft
      )
    },
    test(
      "the lock ends with the rule's retention, counted from its trigger or, until known, from the approval"
    ) {
      val policy  = ObjectRetentionPolicy.parse(true, "compliance", 10).toOption.get
      val created = Instant.parse("2026-11-20T08:00:00Z")
      def r(trigger: RetentionTrigger, yearEnd: Boolean) =
        RetentionRule("k", "k", 120, trigger, yearEnd, dms.kernel.UserId("records-1"), created)
      val inForce = VersionTimes(approvedAt, created, None)
      val ended   = inForce.copy(outOfForceAt = Some(Instant.parse("2030-06-01T12:00:00Z")))
      assertTrue(
        policy.mode == RetentionMode.Compliance,
        policy.retention(Some(r(RetentionTrigger.Approval, false)), inForce, berlin).until == through(
          "2037-03-01"
        ),
        policy.retention(Some(r(RetentionTrigger.Approval, true)), inForce, berlin).until == through(
          "2037-12-31"
        ),
        policy.retention(Some(r(RetentionTrigger.Creation, false)), inForce, berlin).until == through(
          "2036-11-20"
        ),
        // the end of validity is not known at approval: the approval stands in for it (lower bound)
        policy.retention(Some(r(RetentionTrigger.EndOfValidity, true)), inForce, berlin).until ==
          through("2037-12-31"),
        policy.retention(Some(r(RetentionTrigger.EndOfValidity, true)), ended, berlin).until ==
          through("2040-12-31"),
        // no row for the version's rule key
        policy.retention(None, inForce, berlin) == Retention(RetentionMode.Compliance, years(10)),
        ObjectRetentionPolicy.parse(true, "strict", 10).isLeft,
        ObjectRetentionPolicy.parse(true, "governance", 0).isLeft
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

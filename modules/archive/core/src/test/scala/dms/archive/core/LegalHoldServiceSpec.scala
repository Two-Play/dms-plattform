package dms.archive.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.{GenericContainer, PostgreSQLContainer}
import dms.archive.*
import dms.audit.*
import dms.db.queue.ClaimedJob
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.storage.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.sql.DataSource

/** Legal holds (DMS-56, migration V19): the service, the database guards (row-level security, history,
  * destruction guard) and the object legal hold of renditions. Against PostgreSQL and SeaweedFS started by
  * Testcontainers.
  */
object LegalHoldServiceSpec extends ZIOSpecDefault:

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

  private val manager = UserId("records-1")

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

  /** A document with a draft version 1.0; returns the document and version id. */
  private def document(t: (TenantId, UUID, UUID)): ZIO[Env, Any, (DocumentId, UUID)] =
    val (tenant, classId, template) = t
    val doc                         = UUID.randomUUID()
    val version                     = UUID.randomUUID()
    owner {
      val _ =
        sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                              template_version)
                    values ($doc, ${tenant.value}, $classId, 1, ${"Supply contract"}, ${"author-1"}, $template, 1)""".update
          .run()
      sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, class_version, template_id,
                                              template_version, content, created_by, status_changed_by)
            values ($version, ${tenant.value}, $doc, 1, 0, 1, $template, 1, ${Jsonb("{}")}, ${"author-1"},
                    ${"author-1"})""".update.run()
    }.as((DocumentId(doc), version))

  private def owner[A](f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(f)))

  private def app[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  private def holds[A](tenant: TenantId)(f: LegalHoldService => IO[LegalHoldError | DbError, A]) =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(f(LegalHoldService(RecordingAuditTrail(e.audit)))))

  private def lookup[A](tenant: TenantId)(f: LegalHoldLookup => IO[DbError, A]) =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(f(LegalHoldStore)))

  private def hold(name: String, reason: String = "Litigation Müller GmbH") =
    NewLegalHold(name, Some("LIT-2027-001"), reason)

  private def syncJobs(doc: DocumentId): ZIO[Env, DbError, Long] =
    owner(
      sql"""select count(*) from dms.job
            where kind = ${LegalHoldEvents.SyncJobKind} and payload ->> 'documentId' = ${doc.value.toString}"""
        .query[Long]
        .run()
        .head
    )

  private def outbox(hold: LegalHoldId): ZIO[Env, DbError, List[String]] =
    owner(
      sql"""select event_type from dms.outbox_event where aggregate_id = ${hold.value} order by id"""
        .query[String]
        .run()
        .toList
    )

  private def auditOf(action: String) =
    ZIO.serviceWithZIO[Env](_.audit.get.map(_.filter(_.action == action)))

  private def setStatus(version: UUID, to: String)(using DbTx): Int =
    sql"update dms.document_version set status = $to where id = $version".update.run()

  /** Puts the draft into status Archived as the schema owner, with triggers disabled for this transaction:
    * the lifecycle up to the archive (approval, publication, supersession by a successor) is not under test
    * here.
    */
  private def archive(version: UUID): ZIO[Env, DbError, Unit] =
    owner {
      val _ = sql"set local session_replication_role = replica".update.run()
      sql"""update dms.document_version
            set status = 'Archived', rendition_key = ${"r.pdf"}, rendition_sha256 = ${"a" * 64},
                metadata_snapshot = ${Jsonb("{}")}, released_by = ${"approver-1"}, released_at = now()
            where id = $version""".update.run()
    }.unit

  /** Destroys an archived version as the application user. */
  private def destroy(tenant: TenantId, version: UUID): ZIO[Env, DbError, Int] =
    app(tenant)(setStatus(version, "Destroyed"))

  def spec = suite("Legal holds")(
    test("a hold covers documents with reason, audit entries, outbox events and synchronization jobs") {
      for
        t       <- tenant
        d1      <- document(t)
        d2      <- document(t)
        created <- holds(t._1)(
          _.create(hold("Müller"), List(d1._1, d2._1, d1._1), HoldSource.Document, manager)
        )
        docs    <- holds(t._1)(_.documents(created.id))
        held    <- lookup(t._1)(l => l.isUnderLegalHold(d1._1) <*> l.heldAmong(Set(d1._1, d2._1)))
        active  <- lookup(t._1)(_.activeHolds(d2._1))
        events  <- outbox(created.id)
        jobs    <- syncJobs(d1._1)
        holdLog <- auditOf("legal-hold.created").map(_.filter(_.target.id.contains(created.id.value)))
        docLog  <- auditOf("document.legal-hold-set").map(_.filter(_.target.id.contains(d1._1.value)))
      yield assertTrue(
        created.name == "Müller",
        created.matterReference.contains("LIT-2027-001"),
        created.isActive,
        created.documentCount == 2,
        docs.map(_.documentId).toSet == Set(d1._1, d2._1),
        docs.forall(d => d.source == HoldSource.Document && d.addedBy == manager),
        held._1,
        held._2 == Set(d1._1, d2._1),
        active.map(_.id) == List(created.id),
        events == List(LegalHoldEvents.Created),
        jobs == 1L,
        holdLog.map(_.reason) == List(Some("Litigation Müller GmbH")),
        docLog.map(_.target.objectType) == List("document")
      )
    },
    test("setting and adding require a reason; nothing is stored without one") {
      for
        t      <- tenant
        d      <- document(t)
        noName <- holds(t._1)(
          _.create(NewLegalHold(" ", None, "x"), Nil, HoldSource.Document, manager)
        ).either
        blank <- holds(t._1)(
          _.create(hold("A", reason = "  "), List(d._1), HoldSource.Document, manager)
        ).either
        created <- holds(t._1)(_.create(hold("B"), Nil, HoldSource.Document, manager))
        noWhy   <- holds(t._1)(_.addDocuments(created.id, List(d._1), "", HoldSource.Search, manager)).either
        release <- holds(t._1)(_.release(created.id, " ", manager)).either
        held    <- lookup(t._1)(_.isUnderLegalHold(d._1))
      yield assertTrue(
        noName == Left(LegalHoldError.Invalid(List("name is required"))),
        blank == Left(LegalHoldError.Invalid(List("reason is required"))),
        noWhy == Left(LegalHoldError.Invalid(List("reason is required"))),
        release == Left(LegalHoldError.Invalid(List("reason is required"))),
        !held,
        created.documentCount == 0
      )
    },
    test("a document stays under legal hold until the last hold covering it is released") {
      for
        t      <- tenant
        d1     <- document(t)
        d2     <- document(t)
        first  <- holds(t._1)(_.create(hold("First"), List(d1._1), HoldSource.Document, manager))
        second <- holds(t._1)(_.create(hold("Second"), List(d2._1), HoldSource.Document, manager))
        added  <- holds(t._1)(
          _.addDocuments(second.id, List(d1._1, d2._1), "Same facts", HoldSource.Search, manager)
        )
        both     <- lookup(t._1)(_.activeHolds(d1._1))
        released <- holds(t._1)(_.release(first.id, "Settled", manager))
        stillOn  <- lookup(t._1)(_.isUnderLegalHold(d1._1))
        again    <- holds(t._1)(_.release(first.id, "Again", manager)).either
        addLate  <- holds(t._1)(
          _.addDocuments(first.id, List(d2._1), "Late", HoldSource.Document, manager)
        ).either
        _      <- holds(t._1)(_.release(second.id, "Closed", manager))
        off    <- lookup(t._1)(l => l.isUnderLegalHold(d1._1) <*> l.isUnderLegalHold(d2._1))
        active <- holds(t._1)(_.list(Some(true)))
        all    <- holds(t._1)(_.list(None))
        events <- outbox(second.id)
        jobs   <- syncJobs(d1._1)
        lifted <- auditOf("document.legal-hold-released").map(_.filter(_.target.id.contains(d1._1.value)))
      yield assertTrue(
        added == List(d1._1),
        both.map(_.id) == List(first.id, second.id),
        released.release.exists(r => r.reason == "Settled" && r.by == manager),
        released.documentCount == 1,
        stillOn,
        again.left.exists(_.isInstanceOf[LegalHoldError.Released]),
        addLate.left.exists(_.isInstanceOf[LegalHoldError.Released]),
        off == (false, false),
        active.isEmpty,
        all.map(_.id) == List(second.id, first.id),
        events == List(LegalHoldEvents.Created, LegalHoldEvents.DocumentsAdded, LegalHoldEvents.Released),
        // set by the first hold, added to the second, freed by the release of the second
        jobs == 3L,
        lifted.map(_.details.value.fromJson[Json.Obj].toOption.flatMap(_.get("underLegalHold"))) ==
          List(Some(Json.Bool(true)), Some(Json.Bool(false)))
      )
    },
    test("the database refuses to destroy a document under legal hold, and allows it after the release") {
      for
        t       <- tenant
        d       <- document(t)
        created <- holds(t._1)(_.create(hold("Destruction"), List(d._1), HoldSource.Document, manager))
        _       <- archive(d._2)
        refused <- destroy(t._1, d._2).either
        status  <- owner(
          sql"select status from dms.document_version where id = ${d._2}".query[String].run().head
        )
        _     <- holds(t._1)(_.release(created.id, "Closed", manager))
        _     <- destroy(t._1, d._2)
        after <- owner(
          sql"select status from dms.document_version where id = ${d._2}".query[String].run().head
        )
      yield assertTrue(
        refused.left.exists {
          case DbError.AppendOnlyViolation(m) => m.contains("under legal hold")
          case _                              => false
        },
        status == "Archived",
        after == "Destroyed"
      )
    },
    test("holds keep their history and are isolated per tenant") {
      for
        t       <- tenant
        other   <- tenant
        d       <- document(t)
        created <- holds(t._1)(_.create(hold("History"), List(d._1), HoldSource.Document, manager))
        id = created.id.value
        rename <- app(t._1)(
          sql"update dms.legal_hold set released_at = now() where id = $id".update.run()
        ).either
        renamed <- owner(sql"update dms.legal_hold set name = 'Other' where id = $id".update.run()).either
        deleted <- owner(sql"delete from dms.legal_hold where id = $id".update.run()).either
        removed <- owner(sql"delete from dms.legal_hold_document where hold_id = $id".update.run()).either
        noGrant <- app(t._1)(sql"delete from dms.legal_hold_document where hold_id = $id".update.run()).either
        foreign <- holds(other._1)(_.get(created.id)).either
        visible <- app(other._1)(sql"select count(*) from dms.legal_hold_document".query[Long].run().head)
        heldElsewhere <- lookup(other._1)(_.isUnderLegalHold(d._1))
        crossAdd      <- holds(other._1)(s =>
          s.create(hold("Cross"), Nil, HoldSource.Document, manager)
            .flatMap(h => s.addDocuments(h.id, List(d._1), "Cross tenant", HoldSource.Document, manager))
        ).either
        _        <- holds(t._1)(_.release(created.id, "Closed", manager))
        reopened <- owner(
          sql"update dms.legal_hold set released_at = null where id = $id".update.run()
        ).either
      yield assertTrue(
        // released_at alone violates the check constraint: release needs who and why
        rename.isLeft,
        renamed.isLeft,
        deleted.isLeft,
        removed.isLeft,
        noGrant.isLeft,
        foreign.left.exists(_.isInstanceOf[LegalHoldError.NotFound]),
        visible == 0L,
        !heldElsewhere,
        crossAdd.isLeft,
        reopened.isLeft
      )
    },
    test("the renditions of a held document get the object legal hold, best effort for missing objects") {
      for
        e <- ZIO.service[Env]
        t <- tenant
        d <- document(t)
        key = s"renditions/${t._1.value}/${d._1.value}/${d._2}.pdf"
        put <- e.objects.put(
          key,
          ObjectContent.Bytes(Chunk.fromArray("pdf".getBytes(UTF_8))),
          "application/pdf"
        )
        _ <- app(t._1)(
          sql"""update dms.document_version
                set rendition_key = $key, rendition_version = ${put.version.versionId},
                    rendition_sha256 = ${put.sha256.hex}
                where id = ${d._2}""".update.run()
        )
        sync = LegalHoldSync(e.app, e.objects)
        created <- holds(t._1)(_.create(hold("Objects"), List(d._1), HoldSource.Document, manager))
        on      <- sync.sync(t._1, d._1)
        headOn  <- e.objects.head(key, Some(put.version.versionId))
        locked  <- e.objects.delete(key, put.version.versionId).either
        // a new rendition of a held document enqueues the synchronization (trigger of migration V19)
        jobsBefore <- syncJobs(d._1)
        second     <- e.objects.put(
          key,
          ObjectContent.Bytes(Chunk.fromArray("pdf2".getBytes(UTF_8))),
          "application/pdf"
        )
        _ <- app(t._1)(
          sql"""update dms.document_version
                set rendition_version = ${second.version.versionId}, rendition_sha256 = ${second.sha256.hex}
                where id = ${d._2}""".update.run()
        )
        jobsAfter <- syncJobs(d._1)
        viaJob    <- sync.handle(
          ClaimedJob(
            1L,
            Some(t._1),
            LegalHoldEvents.SyncJobKind,
            Jsonb(s"""{"documentId":"${d._1.value}"}"""),
            1,
            10
          )
        )
        secondOn <- e.objects.head(key, Some(second.version.versionId))
        _        <- holds(t._1)(_.release(created.id, "Closed", manager))
        off      <- sync.sync(t._1, d._1)
        headOff  <- e.objects.head(key, Some(second.version.versionId))
        // a rendition reference without object: skipped with a warning, the job does not fail
        missing <- document(t)
        _       <- app(t._1)(
          sql"""update dms.document_version
                set rendition_key = ${"renditions/missing.pdf"}, rendition_version = ${"no-such-version"},
                    rendition_sha256 = ${"a" * 64}
                where id = ${missing._2}""".update.run()
        )
        _          <- holds(t._1)(_.create(hold("Missing"), List(missing._1), HoldSource.Document, manager))
        bestEffort <- sync.sync(t._1, missing._1).either
        badPayload <- sync
          .handle(ClaimedJob(2L, Some(t._1), LegalHoldEvents.SyncJobKind, Jsonb("{}"), 1, 10))
          .either
      yield assertTrue(
        on.held,
        on.objects == List((key, put.version.versionId)),
        headOn.legalHold,
        locked.left.exists(_.isInstanceOf[StorageError.Locked]),
        jobsAfter == jobsBefore + 1,
        viaJob == (),
        secondOn.legalHold,
        !off.held,
        !headOff.legalHold,
        bestEffort.exists(r => r.held && r.objects.isEmpty),
        badPayload.isLeft
      )
    },
    test("attachment files of a held document get the object legal hold like renditions (DMS-114)") {
      for
        e <- ZIO.service[Env]
        t <- tenant
        d <- document(t)
        fileId = UUID.randomUUID()
        key    = s"attachments/${t._1.value}/${d._1.value}/$fileId"
        put <- e.objects.put(key, ObjectContent.Bytes(Chunk.fromArray("annex".getBytes(UTF_8))), "text/plain")
        _   <- app(t._1)(
          sql"""insert into dms.document_attachment_file
                  (id, tenant_id, document_id, sha256, size_bytes, content_type, object_key, object_version,
                   scan_status, scanner, scanned_at, created_by)
                values ($fileId, dms.current_tenant(), ${d._1.value}, ${put.sha256.hex}, 5, ${"text/plain"}, $key,
                        ${put.version.versionId}, ${"NotScanned"}, ${"none"}, now(), ${"author-1"})""".update
            .run()
        )
        sync = LegalHoldSync(e.app, e.objects)
        created <- holds(t._1)(_.create(hold("Annexes"), List(d._1), HoldSource.Document, manager))
        on      <- sync.sync(t._1, d._1)
        headOn  <- e.objects.head(key, Some(put.version.versionId))
        locked  <- e.objects.delete(key, put.version.versionId).either
        _       <- holds(t._1)(_.release(created.id, "Closed", manager))
        off     <- sync.sync(t._1, d._1)
        headOff <- e.objects.head(key, Some(put.version.versionId))
      yield assertTrue(
        on.held,
        on.objects == List((key, put.version.versionId)),
        headOn.legalHold,
        locked.left.exists(_.isInstanceOf[StorageError.Locked]),
        !off.held,
        !headOff.legalHold
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

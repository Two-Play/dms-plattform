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
import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.sql.DataSource

/** Destruction after retention, compaction of working states, removal of discarded drafts' content and the
  * clean-up of orphaned objects (DMS-82, ADR 0054, migration V33). Against PostgreSQL and SeaweedFS started
  * by Testcontainers.
  */
object DestructionServiceSpec extends ZIOSpecDefault:

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

  private val first  = UserId("records-1")
  private val second = UserId("records-2")

  final case class Fixture(tenant: TenantId, classId: UUID, template: UUID)

  /** A record: a document whose version 1.0 went out of force (superseded) at `outOfForce`. */
  final case class Record(
      document: DocumentId,
      version: UUID,
      renditionKey: String,
      rendition: PutResult,
      fileKey: String,
      file: PutResult,
      fileId: UUID
  )

  /** A tenant with a released template, inserted by the schema owner. */
  private def tenant: ZIO[Env, Any, Fixture] =
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
    }.as(Fixture(tenant, classId, template))

  private val content =
    """{"format":1,"sections":[{"id":"s1","title":"Parties","locked":false,"blocks":[]}]}"""

  private def snapshot(rule: String, sha: String) =
    Json
      .Obj(
        "values" -> Json.Obj(
          "title"             -> Json.Obj("type" -> Json.Str("Text"), "value" -> Json.Str("Supply contract")),
          "owner"             -> Json.Obj("type" -> Json.Str("PersonRef"), "userId" -> Json.Str("owner-1")),
          "documentId"        -> Json.Obj("type" -> Json.Str("Text"), "value" -> Json.Str("doc")),
          "retentionRule"     -> Json.Obj("type" -> Json.Str("Text"), "value" -> Json.Str(rule)),
          "renditionChecksum" -> Json.Obj("type" -> Json.Str("Text"), "value" -> Json.Str(sha))
        ),
        "takenAt" -> Json.Str("2014-01-01T00:00:00Z")
      )
      .toJson

  /** A document with version 1.0 out of force since `outOfForce` (status `status`) under `rule`, with three
    * working states, a rendition and an attachment stored in object storage. Inserted by the schema owner
    * with the triggers off: the lifecycle up to there is not under test.
    */
  private def record(
      f: Fixture,
      rule: String = "contract-1y",
      outOfForce: String = "2015-06-01T10:00:00Z",
      status: String = "Superseded",
      renditionRetention: Option[Retention] = None
  ): ZIO[Env, Any, Record] =
    val doc     = UUID.randomUUID()
    val version = UUID.randomUUID()
    val fileId  = UUID.randomUUID()
    val rKey    = s"renditions/${f.tenant.value}/$doc/$version.pdf"
    val fKey    = s"attachments/${f.tenant.value}/$doc/$fileId"
    for
      e         <- ZIO.service[Env]
      rendition <- e.objects.put(
        rKey,
        ObjectContent.Bytes(Chunk.fromArray(s"pdf $version".getBytes(UTF_8))),
        "application/pdf",
        retention = renditionRetention
      )
      file <- e.objects.put(
        fKey,
        ObjectContent.Bytes(Chunk.fromArray(s"annex $doc".getBytes(UTF_8))),
        "text/plain"
      )
      _ <- owner {
        val _ = sql"set local session_replication_role = replica".update.run()
        val _ =
          sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                          template_version, document_number)
                values ($doc, ${f.tenant.value}, ${f.classId}, 1, ${"Supply contract"}, ${"author-1"},
                        ${f.template}, 1, ${s"C-${doc.toString.take(8)}"})""".update.run()
        val _ =
          sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, working_state,
                                                  class_version, template_id, template_version, content,
                                                  field_values, created_by, status_changed_by)
                values ($version, ${f.tenant.value}, $doc, 1, 0, 3, 1, ${f.template}, 1, ${Jsonb(content)},
                        ${Jsonb("""{"title":{"value":{"type":"Text","value":"Supply contract"}}}""")},
                        ${"author-1"}, ${"author-1"})""".update.run()
        (1 to 3).foreach(n =>
          sql"""insert into dms.document_working_state (tenant_id, version_id, working_state, content, field_values,
                                                        saved_at, saved_by)
                values (${f.tenant.value}, $version, $n, ${Jsonb(content)}, ${Jsonb("{}")},
                        ${java.time.OffsetDateTime.parse("2014-01-01T00:00:00Z").plusDays(n.toLong)},
                        ${"author-1"})""".update.run()
        )
        val _ =
          sql"""insert into dms.document_attachment_file (id, tenant_id, document_id, sha256, size_bytes,
                                                          content_type, object_key, object_version, scan_status,
                                                          scanner, scanned_at, created_by)
                values ($fileId, ${f.tenant.value}, $doc, ${file.sha256.hex}, ${file.size}, ${"text/plain"},
                        $fKey, ${file.version.versionId}, ${"NotScanned"}, ${"none"}, now(), ${"author-1"})""".update
            .run()
        val _ =
          sql"""insert into dms.document_version_attachment (id, tenant_id, version_id, file_id, name, added_by)
                values (${UUID.randomUUID()}, ${f.tenant.value}, $version, $fileId, ${"annex.txt"},
                        ${"author-1"})""".update.run()
        sql"""update dms.document_version
              set status = $status, metadata_snapshot = ${Jsonb(snapshot(rule, rendition.sha256.hex))},
                  released_by = ${"approver-1"}, released_at = ${java.time.OffsetDateTime.parse(
            "2014-01-01T00:00:00Z"
          )},
                  rendition_key = $rKey, rendition_version = ${rendition.version.versionId},
                  rendition_sha256 = ${rendition.sha256.hex},
                  status_changed_at = ${java.time.OffsetDateTime.parse(outOfForce)}
              where id = $version""".update.run()
      }
    yield Record(DocumentId(doc), version, rKey, rendition, fKey, file, fileId)

  private def owner[A](f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(f)))

  private def app[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  private def service[A](tenant: TenantId)(f: DestructionService => IO[DestructionError | DbError, A]) =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(f(DestructionService(RecordingAuditTrail(e.audit)))))

  private def holds[A](tenant: TenantId)(f: LegalHoldService => IO[LegalHoldError | DbError, A]) =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(f(LegalHoldService(RecordingAuditTrail(e.audit)))))

  private def cleanup(grace: Duration): ZIO[Env, Nothing, ObjectCleanup] =
    ZIO.serviceWith[Env](e =>
      ObjectCleanup(e.app, e.objects, RecordingAuditTrail(e.audit), OrphanCleanupConfig(grace = grace))
    )

  private def maintenance(config: MaintenanceConfig): ZIO[Env, Nothing, RetentionMaintenance] =
    for
      e <- ZIO.service[Env]
      c <- cleanup(7.days)
    yield RetentionMaintenance(e.app, RecordingAuditTrail(e.audit), c, config)

  private def rules(tenant: TenantId) =
    service(tenant)(s =>
      s.saveRule(
        "contract-1y",
        RetentionRuleInput("Contracts", 12, RetentionTrigger.EndOfValidity, true),
        first
      ) *>
        s.saveRule("long-100y", RetentionRuleInput("Long", 1200, RetentionTrigger.EndOfValidity, true), first)
    )

  private def statusOf(version: UUID) =
    owner(sql"select status from dms.document_version where id = $version".query[String].run().head)

  private def statesOf(version: UUID): ZIO[Env, DbError, Long] =
    owner(
      sql"select count(*) from dms.document_working_state where version_id = $version".query[Long].run().head
    )

  private def linksOf(version: UUID): ZIO[Env, DbError, Long] =
    owner(
      sql"select count(*) from dms.document_version_attachment where version_id = $version"
        .query[Long]
        .run()
        .head
    )

  private def fileRows(file: UUID): ZIO[Env, DbError, Long] =
    owner(sql"select count(*) from dms.document_attachment_file where id = $file".query[Long].run().head)

  private def auditOf(action: String) =
    ZIO.serviceWithZIO[Env](_.audit.get.map(_.filter(_.action == action)))

  private def jobsOf(document: DocumentId) =
    owner(
      sql"""select payload, run_at from dms.job
            where kind = ${RetentionEvents.ObjectCleanupJobKind} and payload ->> 'documentId' = ${document.value.toString}
            order by id""".query[(Jsonb, java.time.OffsetDateTime)].run().toList
    )

  private val Tombstone = """{"format": 1, "sections": []}"""

  def spec = suite("Destruction and clean-up")(
    test(
      "the destruction list shows versions whose retention ended; running ones and those without rule are counted"
    ) {
      for
        f       <- tenant
        _       <- rules(f.tenant)
        ended   <- record(f)
        running <- record(f, rule = "long-100y")
        _       <- record(f, rule = "unknown-rule")
        list    <- service(f.tenant)(_.destructionList)
      yield assertTrue(
        list.candidates.map(_.versionId) == List(ended.version),
        list.candidates.head.retentionEndsOn == LocalDate.of(2016, 12, 31),
        list.candidates.head.rule.key == "contract-1y",
        list.candidates.head.classKey == "contract",
        !list.candidates.head.underLegalHold,
        list.retentionRunning == 1,
        list.withoutRule == 1,
        !list.candidates.exists(_.versionId == running.version)
      )
    },
    test(
      "four eyes: the proposer cannot decide; a second records manager approves and the version is destroyed"
    ) {
      for
        f        <- tenant
        _        <- rules(f.tenant)
        r        <- record(f)
        proposed <- service(f.tenant)(_.propose(List(r.version), "Retention of 2016 ended", first))
        self     <- service(f.tenant)(_.approve(proposed.id, "Myself", first)).either
        selfRej  <- service(f.tenant)(_.reject(proposed.id, "Myself", first)).either
        // The database enforces it as well.
        direct <- app(f.tenant)(
          sql"""update dms.destruction_proposal
                set status = 'Approved', decided_by = ${first.value}, decided_at = now(), decision_reason = ${"x"}
                where id = ${proposed.id}""".update.run()
        ).either
        noReason <- service(f.tenant)(_.approve(proposed.id, " ", second)).either
        approved <- service(f.tenant)(_.approve(proposed.id, "Checked against the register", second))
        again    <- service(f.tenant)(_.approve(proposed.id, "Again", second)).either
        status   <- statusOf(r.version)
        row      <- owner(
          sql"""select content, field_values, metadata_snapshot, content_removed_by, rendition_sha256
                from dms.document_version where id = ${r.version}"""
            .query[(Jsonb, Jsonb, Jsonb, Option[String], Option[String])]
            .run()
            .head
        )
        states <- statesOf(r.version)
        links  <- linksOf(r.version)
        files  <- fileRows(r.fileId)
        jobs   <- jobsOf(r.document)
        outbox <- owner(
          sql"""select event_type, payload ->> 'status' from dms.outbox_event
                where aggregate_id = ${r.document.value} order by id""".query[(String, String)].run().toList
        )
        destroyed <- auditOf(RetentionEvents.Destroyed).map(_.filter(_.target.id.contains(r.document.value)))
        records   <- service(f.tenant)(_.recordsOf(r.document))
        list      <- service(f.tenant)(_.destructionList)
      yield
        val item     = approved.items.head
        val snapshot = row._3.value.fromJson[Json.Obj].toOption.flatMap(_.get("values")).collect {
          case Json.Obj(fields) => fields.map(_._1).toSet
        }
        assertTrue(
          proposed.status == ProposalStatus.Proposed,
          proposed.items.map(_.outcome) == List(ItemOutcome.Pending),
          self.left.exists(_.isInstanceOf[DestructionError.SamePerson]),
          selfRej.left.exists(_.isInstanceOf[DestructionError.SamePerson]),
          direct.left.exists(_.toString.contains("destruction_proposal_four_eyes")),
          noReason.left.exists(_.isInstanceOf[DestructionError.Invalid]),
          approved.status == ProposalStatus.Approved,
          approved.decision.exists(d => d.by == second && d.reason == "Checked against the register"),
          item.outcome == ItemOutcome.Destroyed,
          item.renditionSha256.contains(r.rendition.sha256.hex),
          item.files == List(DestroyedFile("annex.txt", r.file.sha256.hex, "Attachment", released = true)),
          again.left.exists(_.isInstanceOf[DestructionError.Conflict]),
          status == "Destroyed",
          row._1.value.fromJson[Json] == Tombstone.fromJson[Json],
          row._2.value.fromJson[Json] == "{}".fromJson[Json],
          snapshot.contains(Set("documentId", "retentionRule", "renditionChecksum")),
          row._4.contains(second.value),
          // the tombstone keeps the checksum of the destroyed rendition
          row._5.contains(r.rendition.sha256.hex),
          states == 0L,
          links == 0L,
          files == 0L,
          jobs.size == 2,
          outbox.contains(("document.status-changed", "Destroyed")),
          destroyed.size == 1,
          destroyed.head.reason.contains("Checked against the register"),
          destroyed.head.details.value.contains(r.rendition.sha256.hex),
          records.map(_.item.versionId) == List(r.version),
          !list.candidates.exists(_.versionId == r.version)
        )
    },
    test("a legal hold blocks: held versions are not proposed, a hold set later blocks the approval") {
      for
        f     <- tenant
        _     <- rules(f.tenant)
        held  <- record(f)
        later <- record(f)
        _     <- holds(f.tenant)(
          _.create(NewLegalHold("Litigation", None, "Claim"), List(held.document), HoldSource.Document, first)
        )
        list     <- service(f.tenant)(_.destructionList)
        refused  <- service(f.tenant)(_.propose(List(held.version), "Retention ended", first)).either
        proposed <- service(f.tenant)(_.propose(List(later.version), "Retention ended", first))
        _        <- holds(f.tenant)(
          _.create(
            NewLegalHold("Late claim", None, "Claim"),
            List(later.document),
            HoldSource.Document,
            second
          )
        )
        approved <- service(f.tenant)(_.approve(proposed.id, "Confirmed", second))
        status   <- statusOf(later.version)
        blocked  <- auditOf("document.destruction-blocked").map(
          _.filter(_.target.id.contains(later.document.value))
        )
        jobs <- jobsOf(later.document)
      yield assertTrue(
        list.candidates.find(_.versionId == held.version).exists(_.underLegalHold),
        refused.left.exists(_.isInstanceOf[DestructionError.Conflict]),
        approved.items.map(i => (i.outcome, i.outcomeDetail)) == List(
          (ItemOutcome.Blocked, Some("LegalHold"))
        ),
        status == "Superseded",
        blocked.size == 1,
        jobs.isEmpty
      )
    },
    test("a rejected proposal destroys nothing and frees the versions for another proposal") {
      for
        f        <- tenant
        _        <- rules(f.tenant)
        r        <- record(f)
        proposed <- service(f.tenant)(_.propose(List(r.version), "Retention ended", first))
        twice    <- service(f.tenant)(_.propose(List(r.version), "Again", second)).either
        rejected <- service(f.tenant)(_.reject(proposed.id, "Still needed for an audit", second))
        status   <- statusOf(r.version)
        again    <- service(f.tenant)(_.propose(List(r.version), "Audit finished", first))
        open     <- service(f.tenant)(_.list(Some(ProposalStatus.Proposed)))
      yield assertTrue(
        twice.left.exists(_.isInstanceOf[DestructionError.Conflict]),
        rejected.status == ProposalStatus.Rejected,
        rejected.items.map(_.outcome) == List(ItemOutcome.Rejected),
        status == "Superseded",
        open.map(_.id) == List(again.id)
      )
    },
    test("the database destroys a version only along the destruction path, together with its content") {
      for
        f     <- tenant
        r     <- record(f, status = "Archived")
        plain <- app(f.tenant)(
          sql"update dms.document_version set status = 'Destroyed' where id = ${r.version}".update.run()
        ).either
        noApproval <- app(f.tenant)(
          sql"""update dms.document_version
                set status = 'Destroyed', content = ${Jsonb(Tombstone)}, field_values = ${Jsonb("{}")},
                    deviation_report = ${Jsonb("""{"format":1,"deviations":[]}""")},
                    content_removed_at = now(), content_removed_by = ${"records-2"}
                where id = ${r.version}""".update.run()
        ).either
        // Content of a frozen version cannot be emptied without destroying it.
        emptied <- app(f.tenant)(
          sql"""update dms.document_version
                set content = ${Jsonb(Tombstone)}, field_values = ${Jsonb("{}")},
                    deviation_report = ${Jsonb("""{"format":1,"deviations":[]}""")},
                    content_removed_at = now(), content_removed_by = ${"records-2"}
                where id = ${r.version}""".update.run()
        ).either
        states <- app(f.tenant)(
          sql"delete from dms.document_working_state where version_id = ${r.version} and working_state = 3".update
            .run()
        ).either
        status <- statusOf(r.version)
      yield assertTrue(
        plain.left.exists(_.toString.contains("destroyed only together with its content")),
        noApproval.left.exists(_.toString.contains("not part of an approved destruction proposal")),
        emptied.isLeft,
        // the current working state is never removed
        states.left.exists(_.toString.contains("current working state")),
        status == "Archived"
      )
    },
    test("objects of a destroyed version are removed after the commit; Object Lock retention is respected") {
      for
        e <- ZIO.service[Env]
        f <- tenant
        _ <- rules(f.tenant)
        until = Instant.now().plusSeconds(3600)
        r        <- record(f, renditionRetention = Some(Retention(RetentionMode.Governance, until)))
        proposed <- service(f.tenant)(_.propose(List(r.version), "Retention ended", first))
        _        <- service(f.tenant)(_.approve(proposed.id, "Confirmed", second))
        jobs     <- jobsOf(r.document)
        c        <- cleanup(7.days)
        outcomes <- ZIO.foreach(jobs)((payload, _) =>
          ZIO
            .fromEither(payload.value.fromJson[ObjectCleanup.Request])
            .flatMap(req => c.remove(f.tenant, req).map(req.objectKey -> _))
        )
        fileGone  <- e.objects.head(r.fileKey, Some(r.file.version.versionId)).either
        renderOk  <- e.objects.head(r.renditionKey, Some(r.rendition.version.versionId))
        retry     <- jobsOf(r.document)
        viaHandle <- c
          .handle(ClaimedJob(1L, Some(f.tenant), RetentionEvents.ObjectCleanupJobKind, jobs.head._1, 1, 10))
          .either
      yield assertTrue(
        outcomes.toMap == Map(r.fileKey -> ObjectCleanup.Deleted, r.renditionKey -> ObjectCleanup.KeptLocked),
        fileGone.left.exists(_.isInstanceOf[StorageError.NotFound]),
        renderOk.retention.isDefined,
        // retried when the retention ends
        retry.size == 3,
        retry.last._2.toInstant.isAfter(until),
        viaHandle.isRight
      )
    },
    test("an object that is referenced again or belongs to a held document is kept") {
      for
        e          <- ZIO.service[Env]
        f          <- tenant
        r          <- record(f)
        c          <- cleanup(7.days)
        referenced <- c.remove(
          f.tenant,
          ObjectCleanup.Request(Some(r.document.value), r.fileKey, r.file.version.versionId, "test")
        )
        _ <- holds(f.tenant)(
          _.create(NewLegalHold("Hold", None, "Claim"), List(r.document), HoldSource.Document, first)
        )
        put <- e.objects.put(
          s"attachments/${f.tenant.value}/${r.document.value}/loose",
          ObjectContent.Bytes(Chunk(1, 2)),
          "x/y"
        )
        held <- c.remove(
          f.tenant,
          ObjectCleanup.Request(Some(r.document.value), put.version.key, put.version.versionId, "test")
        )
        still <- e.objects.head(put.version.key, Some(put.version.versionId)).either
      yield assertTrue(referenced == ObjectCleanup.Kept, held == ObjectCleanup.Kept, still.isRight)
    },
    test("compaction keeps the current, the latest, the reviewed and the recent working states") {
      import WorkingStateCompaction.State
      val base   = Instant.parse("2026-01-01T00:00:00Z")
      val states = (1 to 6).toList.map(n => State(n, base.plusSeconds(n * 3600L)))
      val pure   = WorkingStateCompaction.removable(
        states,
        current = 6,
        reviewedAt = List(base.plusSeconds(2 * 3600L + 60)),
        keepLatest = 2,
        keepAfter = base.plusSeconds(4 * 3600L + 60)
      )
      for
        f <- tenant
        r <- record(f, status = "Superseded")
        // Three more working states on the draft of a second document, one reviewed through a deviation request.
        draft <- record(f)
        _     <- owner {
          val _ = sql"set local session_replication_role = replica".update.run()
          val _ = sql"""update dms.document_version set status = 'Draft', metadata_snapshot = null,
                          released_by = null, released_at = null, working_state = 6
                        where id = ${draft.version}""".update.run()
          (4 to 6).foreach(n =>
            sql"""insert into dms.document_working_state (tenant_id, version_id, working_state, content, field_values,
                                                          saved_at, saved_by)
                  values (${f.tenant.value}, ${draft.version}, $n, ${Jsonb(content)}, ${Jsonb("{}")},
                          ${java.time.OffsetDateTime.parse("2014-01-01T00:00:00Z").plusDays(n.toLong)},
                          ${"author-1"})""".update.run()
          )
          sql"""insert into dms.document_deviation_request (id, tenant_id, version_id, section_id, reason,
                                                            requested_by, requested_at)
                values (${UUID
              .randomUUID()}, ${f.tenant.value}, ${draft.version}, ${"s1"}, ${"Special terms"},
                        ${"author-1"}, ${java.time.OffsetDateTime.parse("2014-01-02T12:00:00Z")})""".update
            .run()
        }
        // The database refuses to remove a reviewed state.
        reviewed <- app(f.tenant)(
          sql"delete from dms.document_working_state where version_id = ${draft.version} and working_state = 1".update
            .run()
        ).either
        m <- maintenance(
          MaintenanceConfig(keepLatestOpen = 2, keepLatestClosed = 1, keepRecent = Duration.Zero)
        )
        removed <- m.compact(f.tenant)
        closed  <- owner(
          sql"select working_state from dms.document_working_state where version_id = ${r.version} order by 1"
            .query[Int]
            .run()
            .toList
        )
        open <- owner(
          sql"select working_state from dms.document_working_state where version_id = ${draft.version} order by 1"
            .query[Int]
            .run()
            .toList
        )
        logged <- auditOf("document.working-states-compacted")
        // Under legal hold nothing is compacted.
        held <- record(f)
        _    <- holds(f.tenant)(
          _.create(NewLegalHold("Hold", None, "Claim"), List(held.document), HoldSource.Document, first)
        )
        _           <- m.compact(f.tenant)
        kept        <- statesOf(held.version)
        refusedHeld <- app(f.tenant)(
          sql"delete from dms.document_working_state where version_id = ${held.version} and working_state = 1".update
            .run()
        ).either
      yield assertTrue(
        pure == List(1, 3, 4),
        reviewed.left.exists(_.toString.contains("was reviewed")),
        removed.sum == 2 + 3,
        closed == List(3),
        // state 1 was reviewed (the deviation request came after it), 5 and 6 are the latest
        open == List(1, 5, 6),
        logged.size >= 2,
        kept == 3L,
        refusedHeld.left.exists(_.toString.contains("legal hold"))
      )
    },
    test(
      "the content of a discarded draft is removed after the grace period, unless it is under legal hold"
    ) {
      def discarded(f: Fixture, at: String) =
        for
          r <- record(f)
          _ <- owner {
            val _ = sql"set local session_replication_role = replica".update.run()
            sql"""update dms.document_version set status = 'Discarded', metadata_snapshot = null, released_by = null,
                    released_at = null, rendition_key = null, rendition_version = null, rendition_sha256 = null,
                    status_changed_at = ${java.time.OffsetDateTime.parse(at)}
                  where id = ${r.version}""".update.run()
          }
        yield r
      for
        f      <- tenant
        old    <- discarded(f, "2020-01-01T00:00:00Z")
        recent <- discarded(f, java.time.OffsetDateTime.now().minusDays(1).toString)
        held   <- discarded(f, "2020-01-01T00:00:00Z")
        _      <- holds(f.tenant)(
          _.create(NewLegalHold("Hold", None, "Claim"), List(held.document), HoldSource.Document, first)
        )
        m      <- maintenance(MaintenanceConfig(discardedContentGrace = 30.days))
        purged <- m.purgeDiscarded(f.tenant)
        rows   <- owner(
          sql"""select id, content, content_removed_at is not null from dms.document_version
                where id in (${old.version}, ${recent.version}, ${held.version})"""
            .query[(UUID, Jsonb, Boolean)]
            .run()
            .toList
        )
        states <- statesOf(old.version)
        again  <- m.purgeDiscarded(f.tenant)
        logged <- auditOf("document.discarded-content-removed").map(
          _.filter(_.target.id.contains(old.document.value))
        )
      yield
        val byId = rows
          .map((id, c, removed) => id -> (c.value.fromJson[Json] == Tombstone.fromJson[Json], removed))
          .toMap
        assertTrue(
          purged == 1,
          byId(old.version) == (true, true),
          byId(recent.version) == (false, false),
          byId(held.version) == (false, false),
          states == 0L,
          again == 0,
          logged.size == 1,
          logged.head.actor.kind == ActorKind.Technical
        )
    },
    test(
      "orphan clean-up: a dry run reports, the run removes; references, holds, Object Lock and grace keep objects"
    ) {
      for
        e <- ZIO.service[Env]
        f <- tenant
        r <- record(f)
        prefix = s"attachments/${f.tenant.value}/${r.document.value}"
        loose  <- e.objects.put(s"$prefix/loose", ObjectContent.Bytes(Chunk(1, 2, 3)), "x/y")
        locked <- e.objects.put(
          s"$prefix/locked",
          ObjectContent.Bytes(Chunk(4)),
          "x/y",
          retention = Some(Retention(RetentionMode.Governance, Instant.now().plusSeconds(3600)))
        )
        // Another object version of the rendition key that no version references (e.g. a rendering of a draft).
        older <- e.objects.put(r.renditionKey, ObjectContent.Bytes(Chunk(9, 9)), "application/pdf")
        // A stored file nothing links to any more (attachment removed from a draft).
        unlinked <- owner {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"delete from dms.document_version_attachment where file_id = ${r.fileId}".update.run()
        }
        heldRecord <- record(f)
        _          <- holds(f.tenant)(
          _.create(NewLegalHold("Hold", None, "Claim"), List(heldRecord.document), HoldSource.Document, first)
        )
        heldLoose <- e.objects.put(
          s"attachments/${f.tenant.value}/${heldRecord.document.value}/loose",
          ObjectContent.Bytes(Chunk(5)),
          "x/y"
        )
        _ <- owner {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"update dms.document_attachment_file set created_at = now() - interval '30 days'".update.run()
        }
        c          <- cleanup(Duration.Zero)
        dry        <- c.sweep(f.tenant, dryRun = true, first)
        stillLoose <- e.objects.head(loose.version.key, Some(loose.version.versionId)).either
        fileRow    <- fileRows(r.fileId)
        real       <- c.sweep(f.tenant, dryRun = false, first)
        looseGone  <- e.objects.head(loose.version.key, Some(loose.version.versionId)).either
        olderGone  <- e.objects.head(r.renditionKey, Some(older.version.versionId)).either
        current    <- e.objects.head(r.renditionKey, Some(r.rendition.version.versionId)).either
        fileGone   <- e.objects.head(r.fileKey, Some(r.file.version.versionId)).either
        fileAfter  <- fileRows(r.fileId)
        heldKept   <- e.objects.head(heldLoose.version.key, Some(heldLoose.version.versionId)).either
        lockedKept <- e.objects.head(locked.version.key, Some(locked.version.versionId)).either
        fresh      <- e.objects.put(s"$prefix/fresh", ObjectContent.Bytes(Chunk(7)), "x/y")
        graceful   <- cleanup(1.hour).flatMap(_.sweep(f.tenant, dryRun = false, first))
        freshKept  <- e.objects.head(fresh.version.key, Some(fresh.version.versionId)).either
        runs       <- e.app.transact(f.tenant)(c.runs(10))
        logged     <- auditOf("storage.cleanup-run")
      yield
        def outcome(report: CleanupReport, key: String, version: String) =
          report.entries.find(en => en.key == key && en.version == version).map(_.outcome)
        assertTrue(
          unlinked == 1,
          dry.dryRun,
          outcome(dry, loose.version.key, loose.version.versionId).contains(ObjectCleanup.WouldDelete),
          outcome(dry, r.renditionKey, older.version.versionId).contains(ObjectCleanup.WouldDelete),
          // the unlinked file: its row and its object
          dry.entries.filter(_.key == r.fileKey).map(_.outcome).toSet == Set(
            "FileWouldDelete",
            ObjectCleanup.WouldDelete
          ),
          outcome(dry, locked.version.key, locked.version.versionId).contains(ObjectCleanup.KeptLocked),
          outcome(dry, heldLoose.version.key, heldLoose.version.versionId).contains(ObjectCleanup.KeptHeld),
          dry.summary.filesDeleted == 1,
          stillLoose.isRight,
          fileRow == 1L,
          real.summary.deleted >= 3,
          looseGone.isLeft,
          olderGone.isLeft,
          current.isRight,
          fileGone.isLeft,
          fileAfter == 0L,
          heldKept.isRight,
          lockedKept.isRight,
          outcome(graceful, fresh.version.key, fresh.version.versionId).contains(ObjectCleanup.KeptRecent),
          freshKept.isRight,
          runs.size == 3,
          runs.head.id == graceful.id,
          logged.size >= 3
        )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

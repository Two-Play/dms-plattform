package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId, VersionNo}
import dms.templates.TemplateContent
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Attachments in PostgreSQL (migration V26), tested with plain SQL as the application role: row-level
  * security, the freeze of links once their version leaves Draft, immutable files; and [[AttachmentService]]
  * on top (audit entries, reuse of stored files, conflicts).
  */
object AttachmentSchemaSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, audit: Ref[List[AuditEvent]])

  /** A tenant with a released template and a document with a draft version. */
  final case class Fixture(tenant: TenantId, templateId: UUID, document: UUID, version: UUID)

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

  private val content = Jsonb("""{"format":1,"sections":[],"computed":[]}""")
  private val sha     = "b" * 64

  private def fixture: ZIO[Env, DbError, Fixture] =
    val tenant     = TenantId.random()
    val classId    = UUID.randomUUID()
    val templateId = UUID.randomUUID()
    val document   = UUID.randomUUID()
    val version    = UUID.randomUUID()
    ZIO.serviceWithZIO[Env](
      _.owner
        .transactSystem(Db.sql {
          val _ = sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run()
          val _ = sql"""insert into dms.document_class (id, tenant_id, key, created_by)
                        values ($classId, ${tenant.value}, ${"contract"}, ${"admin"})""".update.run()
          val _ =
            sql"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
                  values (${tenant.value}, $classId, 1, ${"Contract"}, ${"admin"})""".update.run()
          val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                        values ($templateId, ${tenant.value}, ${"designer"})""".update.run()
          val _ = sql"""insert into dms.template_version
                          (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                           content, created_by, status_changed_by)
                        values (${tenant.value}, $templateId, 1, ${"Contract"}, $classId, 1, ${"Testing"}, $content,
                                ${"designer"}, ${"designer"})""".update.run()
          val _ =
            sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
                  where template_id = $templateId""".update.run()
          val _ =
            sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                            template_version)
                  values ($document, ${tenant.value}, $classId, 1, ${"Contract"}, ${"author"}, $templateId, 1)""".update
              .run()
          sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, class_version, template_id,
                                                  template_version, content, created_by, status_changed_by)
                values ($version, ${tenant.value}, $document, 1, 0, 1, $templateId, 1, $content, ${"author"},
                        ${"author"})""".update.run()
        })
        .as(Fixture(tenant, templateId, document, version))
    )

  private def asApp[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  private def insertFile(document: UUID, hash: String = sha)(using DbTx): UUID =
    val id = UUID.randomUUID()
    val _  = sql"""insert into dms.document_attachment_file
                     (id, tenant_id, document_id, sha256, size_bytes, content_type, object_key, object_version,
                      scan_status, scanner, scanned_at, created_by)
                   values ($id, dms.current_tenant(), $document, $hash, 10, ${"application/pdf"},
                           ${s"attachments/$id"}, ${"v1"}, ${"NotScanned"}, ${"none"}, now(), ${"author"})""".update
      .run()
    id

  private def link(version: UUID, file: UUID, name: String)(using DbTx): UUID =
    val id = UUID.randomUUID()
    val _  =
      sql"""insert into dms.document_version_attachment (id, tenant_id, version_id, file_id, name, added_by)
                   values ($id, dms.current_tenant(), $version, $file, $name, ${"author"})""".update.run()
    id

  private def setStatus(version: UUID, status: String)(using DbTx): Int =
    sql"update dms.document_version set status = $status where id = $version".update.run()

  private def guarded(e: Either[DbError, ?]): Boolean =
    e.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation])

  private def service = ZIO.serviceWith[Env](e => AttachmentService(RecordingAuditTrail(e.audit)))

  /** The draft as the service sees it (only the fields it uses matter). */
  private def draftOf(f: Fixture, status: DocumentStatus = DocumentStatus.Draft) =
    DocumentVersion(
      f.version,
      DocumentId(f.document),
      VersionNo(1, 0),
      1,
      status,
      ChangeKind.Major,
      None,
      "",
      1,
      TemplateId(f.templateId),
      1,
      TemplateContent(Nil),
      Map.empty,
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      None,
      None
    )

  private def fileFor(f: Fixture, hash: String) =
    AttachmentFile(
      UUID.randomUUID(),
      DocumentId(f.document),
      hash,
      42L,
      "text/plain",
      s"attachments/${f.tenant.value}/${f.document}/x",
      "v1",
      ScanStatus.NotScanned,
      "none",
      Instant.now(),
      UserId("author")
    )

  def spec = suite("Attachments in the database")(
    test("links change only while their version is a draft; only the name changes") {
      for
        f       <- fixture
        file    <- asApp(f.tenant)(insertFile(f.document))
        other   <- asApp(f.tenant)(insertFile(f.document, "c" * 64))
        id      <- asApp(f.tenant)(link(f.version, file, "a.pdf"))
        renamed <- asApp(f.tenant)(
          sql"update dms.document_version_attachment set name = 'b.pdf' where id = $id".update.run()
        )
        refile <- asApp(f.tenant)(
          sql"update dms.document_version_attachment set file_id = $other where id = $id".update.run()
        ).either
        _      <- asApp(f.tenant)(setStatus(f.version, "InReview"))
        added  <- asApp(f.tenant)(link(f.version, other, "c.pdf")).either
        rename <- asApp(f.tenant)(
          sql"update dms.document_version_attachment set name = 'x' where id = $id".update.run()
        ).either
        delete <- asApp(f.tenant)(
          sql"delete from dms.document_version_attachment where id = $id".update.run()
        ).either
        _       <- asApp(f.tenant)(setStatus(f.version, "Draft"))
        removed <- asApp(f.tenant)(
          sql"delete from dms.document_version_attachment where id = $id".update.run()
        )
      yield assertTrue(
        renamed == 1,
        // only the name is granted for updates
        refile.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        guarded(added),
        guarded(rename),
        guarded(delete),
        removed == 1
      )
    },
    test("files are immutable and belong to one document; names are checked and unique per version") {
      for
        f      <- fixture
        g      <- fixture
        file   <- asApp(f.tenant)(insertFile(f.document))
        update <- asApp(f.tenant)(
          sql"update dms.document_attachment_file set size_bytes = 1 where id = $file".update.run()
        ).either
        delete <- asApp(f.tenant)(
          sql"delete from dms.document_attachment_file where id = $file".update.run()
        ).either
        // Even the schema owner, who has every grant, cannot change a file: the trigger refuses.
        ownerUpdate <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(
                sql"update dms.document_attachment_file set size_bytes = 1 where id = $file".update.run()
              )
            )
            .either
        )
        sameBytes <- asApp(f.tenant)(insertFile(f.document)).either
        // A file of another document cannot be linked: the guard compares the documents.
        otherDoc <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(Db.sql {
            val _ = sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id,
                                                    template_id, template_version)
                          select ${UUID
                .randomUUID()}, tenant_id, class_id, class_version, title, author_id, template_id,
                                 template_version from dms.document where id = ${f.document}""".update.run()
            sql"select id from dms.document where tenant_id = ${f.tenant.value} and id <> ${f.document}"
              .query[UUID]
              .run()
              .head
          })
        )
        foreignFile <- asApp(f.tenant)(insertFile(otherDoc))
        crossDoc    <- asApp(f.tenant)(link(f.version, foreignFile, "x.pdf")).either
        slash       <- asApp(f.tenant)(link(f.version, file, "a/b.pdf")).either
        blank       <- asApp(f.tenant)(link(f.version, file, "  ")).either
        _           <- asApp(f.tenant)(link(f.version, file, "Annex.pdf"))
        second      <- asApp(f.tenant)(insertFile(f.document, "d" * 64))
        sameName    <- asApp(f.tenant)(link(f.version, second, "ANNEX.pdf")).either
        twice       <- asApp(f.tenant)(link(f.version, file, "again.pdf")).either
        // Row-level security: another tenant sees nothing and cannot reference the rows.
        foreign <- asApp(g.tenant)(
          sql"select count(*) from dms.document_attachment_file".query[Long].run().head
        )
        links <- asApp(g.tenant)(
          sql"select count(*) from dms.document_version_attachment".query[Long].run().head
        )
        crossTen <- asApp(g.tenant)(link(g.version, file, "stolen.pdf")).either
      yield assertTrue(
        update.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        delete.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        guarded(ownerUpdate),
        sameBytes.left.exists(_.isInstanceOf[DbError.UniqueViolation]),
        guarded(crossDoc),
        slash.isLeft,
        blank.isLeft,
        sameName.left.exists(_.isInstanceOf[DbError.UniqueViolation]),
        twice.left.exists(_.isInstanceOf[DbError.UniqueViolation]),
        foreign == 0L,
        links == 0L,
        crossTen.isLeft
      )
    },
    test("a file stored for a held document enqueues the legal hold synchronization") {
      for
        f <- fixture
        _ <- asApp(f.tenant) {
          val hold = UUID.randomUUID()
          val _    = sql"""insert into dms.legal_hold (id, tenant_id, name, reason, created_by)
                           values ($hold, dms.current_tenant(), ${"Matter"}, ${"Claim"}, ${"records"})""".update
            .run()
          sql"""insert into dms.legal_hold_document (tenant_id, hold_id, document_id, added_via, reason, added_by)
                values (dms.current_tenant(), $hold, ${f.document}, ${"Document"}, ${"Claim"}, ${"records"})""".update
            .run()
        }
        before <- jobs(f.document)
        _      <- asApp(f.tenant)(insertFile(f.document))
        after  <- jobs(f.document)
      yield assertTrue(after == before + 1)
    },
    test(
      "the service links files to the draft with audit entries, reuses stored files and refuses frozen versions"
    ) {
      for
        f     <- fixture
        svc   <- service
        db    <- ZIO.serviceWith[Env](_.app)
        first <- db.transact(f.tenant)(
          svc.add(draftOf(f), fileFor(f, "e" * 64), "Notes.txt", UserId("author"))
        )
        // The same content again (e.g. a concurrent upload): the stored file is reused, the new one not inserted.
        reuse <- db
          .transact(f.tenant)(svc.add(draftOf(f), fileFor(f, "e" * 64), "Copy.txt", UserId("author")))
          .either
        existing <- db.transact(f.tenant)(svc.existingFile(DocumentId(f.document), "e" * 64))
        clash    <- db
          .transact(f.tenant)(svc.add(draftOf(f), fileFor(f, "f" * 64), "notes.TXT", UserId("author")))
          .either
        renamed <- db.transact(f.tenant)(svc.rename(draftOf(f), first._1.id, "Minutes.txt", UserId("author")))
        unknown <- db.transact(f.tenant)(svc.remove(draftOf(f), UUID.randomUUID(), UserId("author"))).either
        _       <- asApp(f.tenant)(setStatus(f.version, "InReview"))
        frozen  <- db.transact(f.tenant)(svc.remove(draftOf(f), first._1.id, UserId("author"))).either
        stale   <- db
          .transact(f.tenant)(
            svc.remove(draftOf(f, DocumentStatus.InReview), first._1.id, UserId("author"))
          )
          .either
        events <- ZIO.serviceWithZIO[Env](_.audit.get)
        mine = events.filter(_.target.id.contains(f.document)).map(_.action)
      yield assertTrue(
        first._2,
        first._1.name == "Notes.txt",
        first._1.sha256 == "e" * 64,
        first._1.scanStatus == ScanStatus.NotScanned,
        reuse.left.exists {
          case DocumentError.Conflict(m) => m.contains("already attached")
          case _                         => false
        },
        existing.map(_.id).contains(first._1.fileId),
        clash.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        renamed.name == "Minutes.txt",
        unknown.left.exists(_.isInstanceOf[DocumentError.NotFound]),
        frozen.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        stale.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        mine == List("document.attachment-added", "document.attachment-renamed")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential

  private def jobs(document: UUID): ZIO[Env, DbError, Long] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"""select count(*) from dms.job
                where kind = 'archive.legal-hold-sync' and payload ->> 'documentId' = ${document.toString}"""
            .query[Long]
            .run()
            .head
        )
      )
    )

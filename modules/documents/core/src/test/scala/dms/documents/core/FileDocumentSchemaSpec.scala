package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** File documents and Word templates in PostgreSQL (migration V27), tested with plain SQL as the application
  * role: the kind of a document, the file of a version frozen from submission on, the Word file of a template
  * version frozen from testing on, and row-level security.
  */
object FileDocumentSchemaSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db)

  /** A tenant with a released template, a draft template version and a file document with a draft version. */
  final case class Fixture(
      tenant: TenantId,
      templateId: UUID,
      draftTemplate: UUID,
      document: UUID,
      version: UUID,
      structured: UUID,
      structuredVersion: UUID
  )

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

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
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
    yield Env(Db(app), Db(owner))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val content = Jsonb("""{"format":1,"sections":[],"computed":[]}""")
  private val sha     = "b" * 64

  private def fixture: ZIO[Env, DbError, Fixture] =
    val tenant        = TenantId.random()
    val classId       = UUID.randomUUID()
    val templateId    = UUID.randomUUID()
    val draftTemplate = UUID.randomUUID()
    val document      = UUID.randomUUID()
    val version       = UUID.randomUUID()
    val structured    = UUID.randomUUID()
    val structuredV   = UUID.randomUUID()
    ZIO.serviceWithZIO[Env](
      _.owner
        .transactSystem(Db.sql {
          val _ = sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run()
          val _ = sql"""insert into dms.document_class (id, tenant_id, key, created_by)
                        values ($classId, ${tenant.value}, ${"contract"}, ${"admin"})""".update.run()
          val _ =
            sql"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
                  values (${tenant.value}, $classId, 1, ${"Contract"}, ${"admin"})""".update.run()
          List(templateId, draftTemplate).foreach { t =>
            val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                          values ($t, ${tenant.value}, ${"designer"})""".update.run()
            val _ = sql"""insert into dms.template_version
                            (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                             content, created_by, status_changed_by)
                          values (${tenant.value}, $t, 1, ${"Contract"}, $classId, 1, ${"Draft"}, $content,
                                  ${"designer"}, ${"designer"})""".update.run()
          }
          val _ =
            sql"update dms.template_version set status = 'Testing' where template_id = $templateId".update
              .run()
          val _ =
            sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
                  where template_id = $templateId""".update.run()
          List((document, version, "File"), (structured, structuredV, "Structured")).foreach { (d, v, kind) =>
            val _ =
              sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                              template_version, kind)
                    values ($d, ${tenant.value}, $classId, 1, ${"Contract"}, ${"author"}, $templateId, 1, $kind)""".update
                .run()
            val _ =
              sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, class_version,
                                                      template_id, template_version, content, created_by,
                                                      status_changed_by)
                    values ($v, ${tenant.value}, $d, 1, 0, 1, $templateId, 1, $content, ${"author"},
                            ${"author"})""".update.run()
          }
        })
        .as(Fixture(tenant, templateId, draftTemplate, document, version, structured, structuredV))
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

  private def setFile(version: UUID, file: UUID, name: String)(using DbTx): Int =
    sql"""insert into dms.document_version_file (version_id, tenant_id, file_id, name, set_by)
          values ($version, dms.current_tenant(), $file, $name, ${"author"})
          on conflict (version_id) do update set file_id = excluded.file_id, name = excluded.name""".update
      .run()

  private def setStatus(version: UUID, status: String)(using DbTx): Int =
    sql"update dms.document_version set status = $status where id = $version".update.run()

  private def wordFile(template: UUID, name: String)(using DbTx): Int =
    sql"""insert into dms.template_word_file
            (template_id, version, tenant_id, name, sha256, size_bytes, object_key, object_version, scan_status,
             fields, set_by)
          values ($template, 1, dms.current_tenant(), $name, $sha, 10, ${"templates/x"}, ${"v1"}, ${"Clean"},
                  ${Jsonb(
        """[{"key":"customer","label":"Kunde","type":"Text","required":true,"source":"Manual"}]"""
      )},
                  ${"designer"})
          on conflict (template_id, version) do update set name = excluded.name""".update.run()

  def spec = suite("File documents and Word templates in the database")(
    test("documents are structured by default; the kind cannot be changed") {
      for
        f     <- fixture
        kinds <- asApp(f.tenant)(sql"select kind from dms.document order by kind".query[String].run().toList)
        changed <- asApp(f.tenant)(
          sql"update dms.document set kind = 'Structured' where id = ${f.document}".update.run()
        ).either
        default <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(
            Db.sql(
              sql"select column_default from information_schema.columns where table_name = 'document' and column_name = 'kind'"
                .query[String]
                .run()
                .head
            )
          )
        )
      yield assertTrue(kinds == List("File", "Structured"), changed.isLeft, default.contains("Structured"))
    },
    test("the file of a version changes only while it is a draft of a file document and is never deleted") {
      for
        f        <- fixture
        first    <- asApp(f.tenant)(insertFile(f.document))
        second   <- asApp(f.tenant)(insertFile(f.document, "c" * 64))
        foreign  <- asApp(f.tenant)(insertFile(f.structured, "d" * 64))
        set      <- asApp(f.tenant)(setFile(f.version, first, "Vertrag.docx"))
        replaced <- asApp(f.tenant)(setFile(f.version, second, "Vertrag v2.docx"))
        values   <- asApp(f.tenant)(
          sql"""update dms.document_version_file set word_values = ${Jsonb("""{"customer":"ACME"}""")}
                where version_id = ${f.version}""".update.run()
        )
        wrongDoc <- asApp(f.tenant)(setFile(f.version, foreign, "x.docx")).either
        onStruct <- asApp(f.tenant)(setFile(f.structuredVersion, foreign, "x.docx")).either
        badName  <- asApp(f.tenant)(setFile(f.version, first, "a/b.docx")).either
        _        <- asApp(f.tenant)(setStatus(f.version, "InReview"))
        frozen   <- asApp(f.tenant)(setFile(f.version, first, "late.docx")).either
        frozenV  <- asApp(f.tenant)(
          sql"""update dms.document_version_file set word_values = '{}'::jsonb
                where version_id = ${f.version}""".update.run()
        ).either
        deleted <- asApp(f.tenant)(
          sql"delete from dms.document_version_file where version_id = ${f.version}".update.run()
        ).either
        stored <- asApp(f.tenant)(
          sql"select name from dms.document_version_file where version_id = ${f.version}"
            .query[String]
            .run()
            .toList
        )
      yield assertTrue(
        set == 1,
        replaced == 1,
        values == 1,
        wrongDoc.isLeft,
        onStruct.isLeft,
        badName.isLeft,
        frozen.isLeft,
        frozenV.isLeft,
        deleted.isLeft,
        stored == List("Vertrag v2.docx")
      )
    },
    test(
      "a draft's original is removed only while it is a draft; a stored file is deleted only when unreferenced and not held"
    ) {
      for
        f          <- fixture
        file       <- asApp(f.tenant)(insertFile(f.document))
        _          <- asApp(f.tenant)(setFile(f.version, file, "Vertrag.docx"))
        referenced <- asApp(f.tenant)(
          sql"delete from dms.document_attachment_file where id = $file".update.run()
        ).either
        changed <- asApp(f.tenant)(
          sql"update dms.document_attachment_file set size_bytes = 11 where id = $file".update.run()
        ).either
        removed <- asApp(f.tenant)(
          sql"delete from dms.document_version_file where version_id = ${f.version}".update.run()
        )
        held <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(Db.sql {
            val hold = UUID.randomUUID()
            val _    = sql"""insert into dms.legal_hold (id, tenant_id, name, reason, created_by)
                          values ($hold, ${f.tenant.value}, ${"Matter"}, ${"Pending"}, ${"records"})""".update
              .run()
            sql"""insert into dms.legal_hold_document (tenant_id, hold_id, document_id, added_via, reason, added_by)
                  values (${f.tenant.value}, $hold, ${f.document}, ${"Document"}, ${"Pending"}, ${"records"})""".update
              .run()
          })
        )
        underHold <- asApp(f.tenant)(
          sql"delete from dms.document_attachment_file where id = $file".update.run()
        ).either
        other   <- asApp(f.tenant)(insertFile(f.structured, "e" * 64))
        deleted <- asApp(f.tenant)(
          sql"delete from dms.document_attachment_file where id = $other".update.run()
        )
      yield assertTrue(
        referenced.isLeft,
        changed.isLeft,
        removed == 1,
        held == 1,
        underHold.isLeft,
        deleted == 1
      )
    },
    test("the Word file of a template version changes only while the version is a draft") {
      for
        f        <- fixture
        released <- asApp(f.tenant)(wordFile(f.templateId, "a.docx")).either
        draft    <- asApp(f.tenant)(wordFile(f.draftTemplate, "a.docx"))
        replaced <- asApp(f.tenant)(wordFile(f.draftTemplate, "b.docx"))
        _        <- asApp(f.tenant)(
          sql"update dms.template_version set status = 'Testing' where template_id = ${f.draftTemplate}".update
            .run()
        )
        frozen  <- asApp(f.tenant)(wordFile(f.draftTemplate, "c.docx")).either
        deleted <- asApp(f.tenant)(
          sql"delete from dms.template_word_file where template_id = ${f.draftTemplate}".update.run()
        ).either
      yield assertTrue(released.isLeft, draft == 1, replaced == 1, frozen.isLeft, deleted.isLeft)
    },
    test("other tenants see neither files of versions nor Word templates") {
      for
        f     <- fixture
        other <- fixture
        file  <- asApp(f.tenant)(insertFile(f.document))
        _     <- asApp(f.tenant)(setFile(f.version, file, "a.docx"))
        _     <- asApp(f.tenant)(wordFile(f.draftTemplate, "t.docx"))
        files <- asApp(other.tenant)(
          sql"select count(*) from dms.document_version_file".query[Long].run().head
        )
        words <- asApp(other.tenant)(sql"select count(*) from dms.template_word_file".query[Long].run().head)
        mine  <- asApp(f.tenant)(sql"select count(*) from dms.template_word_file".query[Long].run().head)
        cross <- asApp(other.tenant)(setFile(other.version, file, "x.docx")).either
      yield assertTrue(files == 0L, words == 0L, mine == 1L, cross.isLeft)
    }
  ).provideShared(env) @@ TestAspect.sequential

package dms.documents.core

import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.Migrations
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.sql.DriverManager
import java.util.UUID

/** V53 fills the list of editors from what the versions already have (DMS-159, ADR 0075): existing data is
  * only there before the migration, so this test stops short of it, adds data and migrates on.
  */
object VersionEditorsMigrationSpec extends ZIOSpecDefault:

  private val tenant    = UUID.randomUUID()
  private val classId   = UUID.randomUUID()
  private val template  = UUID.randomUUID()
  private val document  = UUID.randomUUID()
  private val other     = UUID.randomUUID()
  private val version   = UUID.randomUUID()
  private val untouched = UUID.randomUUID()
  private val content   = """{"format":1,"sections":[],"computed":[]}"""
  private val hash      = "\\x" + "ab" * 32

  private def audit(seq: Int, action: String, actor: String, label: String, target: UUID) =
    s"""insert into dms.audit_event (tenant_id, occurred_at, actor_id, actor_kind, action, object_type, object_id,
                                     object_version, details, channel, chain_seq, prev_hash, hash)
        values ('$tenant', now(), '$actor', 'person', '$action', 'document', '$document', '$label',
                '{"versionId":"$target"}', 'api', $seq, ${
        if seq == 1 then "null" else s"'$hash'"
      }, '$hash')"""

  private val existing = List(
    s"insert into dms.tenant (id, name) values ('$tenant', 'Tenant')",
    s"""insert into dms.document_class (id, tenant_id, key, created_by)
        values ('$classId', '$tenant', 'contract', 'admin')""",
    s"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
        values ('$tenant', '$classId', 1, 'Contract', 'admin')""",
    s"insert into dms.template (id, tenant_id, created_by) values ('$template', '$tenant', 'designer')",
    s"""insert into dms.template_version
          (tenant_id, template_id, version, name, document_class_id, document_class_version, status, content,
           created_by, status_changed_by)
        values ('$tenant', '$template', 1, 'Contract', '$classId', 1, 'Draft', '$content', 'designer', 'designer')""",
    s"update dms.template_version set status = 'Testing' where template_id = '$template'",
    s"""update dms.template_version set status = 'Released', released_by = 'designer-2', released_at = now()
        where template_id = '$template'""",
    s"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                  template_version)
        values ('$document', '$tenant', '$classId', 1, 'Contract', 'author', '$template', 1)""",
    s"""insert into dms.document_version (id, tenant_id, document_id, major, minor, scope, class_version, template_id,
                                          template_version, content, created_by, status_changed_by)
        values ('$version', '$tenant', '$document', 1, 0, '', 1, '$template', 1, '$content', 'author', 'author')""",
    s"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                  template_version)
        values ('$other', '$tenant', '$classId', 1, 'Contract', 'starter', '$template', 1)""",
    s"""insert into dms.document_version (id, tenant_id, document_id, major, minor, scope, class_version, template_id,
                                          template_version, content, created_by, status_changed_by)
        values ('$untouched', '$tenant', '$other', 1, 0, '', 1, '$template', 1, '$content', 'starter', 'starter')""",
    s"""insert into dms.document_working_state (tenant_id, version_id, working_state, content, field_values, saved_by)
        values ('$tenant', '$version', 2, '$content', '{}', 'saver')""",
    s"""insert into dms.document_deviation_request (id, tenant_id, version_id, section_id, reason, requested_by,
                                                    withdrawn_by, withdrawn_at)
        values ('${UUID
        .randomUUID()}', '$tenant', '$version', 'x', 'Why', 'requester', 'withdrawer', now())""",
    // Compaction took the working state of this change; the audit trail still names who made it.
    audit(1, "document.draft-changed", "compacted", "1.0-draft.3", version),
    // A change summary leaves no working state.
    audit(2, "document.change-reason-changed", "summarizer", "1.0-draft.3", version),
    // Reading or approving changes nothing, and a number given to a version without draft changes the document.
    audit(3, "document.approved", "approver", "1.0", version),
    audit(4, "document.number-assigned", "registrar", "1.0", version)
  )

  private def inContainer[A](f: PostgreSQLContainer => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking {
      val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)(f)

  private val appRole =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |end $$""".stripMargin

  private def editors(c: PostgreSQLContainer, of: UUID): Set[String] =
    val connection = DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
    try
      val rows = connection
        .createStatement()
        .executeQuery(s"select user_id from dms.document_version_editor where version_id = '$of'")
      Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toSet
    finally connection.close()

  def spec = suite("V53 version editors")(
    test(
      "existing versions get everyone who changed them, also from changes that left only the audit trail"
    ) {
      inContainer { c =>
        for
          _ <- ZIO.attemptBlocking {
            val connection = DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
            try
              connection.createStatement().execute(appRole)
              val _ = Flyway
                .configure()
                .dataSource(c.jdbcUrl, c.username, c.password)
                .locations(Migrations.Location)
                .schemas("dms")
                .defaultSchema("dms")
                .target("47")
                .load()
                .migrate()
              existing.foreach(connection.createStatement().execute)
            finally connection.close()
          }
          _       <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
          changed <- ZIO.attemptBlocking(editors(c, version))
          started <- ZIO.attemptBlocking(editors(c, untouched))
        yield assertTrue(
          changed == Set("author", "saver", "requester", "withdrawer", "compacted", "summarizer"),
          started == Set("starter")
        )
      }
    }
  ) @@ TestAspect.withLiveClock

package dms.audit.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.{GenericContainer, PostgreSQLContainer}
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.TenantId
import dms.storage.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource
import scala.util.Using

/** Checkpoints of the chain head in write-once storage (DMS-74), against PostgreSQL and SeaweedFS. */
object AuditCheckpointsSpec extends ZIOSpecDefault:

  final case class Env(
      app: Db,
      owner: Db,
      trail: AuditTrail,
      checkpoints: AuditCheckpoints,
      store: ObjectStore
  )

  private val seaweedImage =
    "chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private def started[C <: com.dimafeng.testcontainers.SingleContainer[?]](
      make: => C
  ): ZIO[Scope, Throwable, C] =
    ZIO.acquireRelease(ZIO.attemptBlocking { val c = make; c.start(); c })(c =>
      ZIO.attemptBlocking(c.stop()).orDie
    )

  private val env: ZLayer[Any, Throwable, Env] = ZLayer.scoped(
    for
      pg <- started(PostgreSQLContainer(DockerImageName.parse("postgres:17")))
      s3 <- started(
        GenericContainer(
          dockerImage = seaweedImage,
          exposedPorts = Seq(8333),
          env = Map("AWS_ACCESS_KEY_ID" -> "dms-test", "AWS_SECRET_ACCESS_KEY" -> "dms-test-secret"),
          command = Seq("mini", "-dir=/data"),
          waitStrategy = Wait.forLogMessage(".*All enabled components are running.*", 1)
        )
      )
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _   <- Migrations.migrate(pg.jdbcUrl, pg.username, pg.password)
      app <- Db
        .dataSource(DbConfig(pg.jdbcUrl, "dms_app_user", "dms-app-test", 4))
        .build
        .map(_.get[DataSource])
      owner <- Db.dataSource(DbConfig(pg.jdbcUrl, pg.username, pg.password, 2)).build.map(_.get[DataSource])
      storage = StorageConfig(
        s"http://${s3.host}:${s3.mappedPort(8333)}",
        "us-east-1",
        "dms-audit-test",
        "dms-test",
        "dms-test-secret"
      )
      client <- (ZLayer.succeed(storage) >>> ObjectStore.client).build
        .map(_.get[software.amazon.awssdk.services.s3.S3Client])
      _ <- ObjectStore.bucketSetup
        .provide(ZLayer.succeed(client), ZLayer.succeed(storage))
        .mapError(e => RuntimeException(e.toString))
      store = S3ObjectStore(client, storage.bucket)
      trail = AuditTrailLive(Db(app), TsaTrust(Nil))
    yield Env(
      Db(app),
      Db(owner),
      trail,
      AuditCheckpoints(Db(app), trail, store, CheckpointConfig(1.day)),
      store
    )
  )

  private def execute(statement: String)(using tx: DbTx): Unit =
    Using.resource(tx.connection.createStatement())(_.execute(statement)): Unit

  private def newTenant: ZIO[Env, DbError, TenantId] =
    ZIO.serviceWithZIO[Env] { e =>
      val id = TenantId.random()
      e.owner
        .transactSystem(
          Db.sql(execute(s"insert into dms.tenant (id, name) values ('${id.value}', 'Tenant')"))
        )
        .as(id)
    }

  private def record(count: Int, tenant: TenantId): ZIO[Env, DbError, Unit] =
    ZIO.serviceWithZIO[Env](e =>
      ZIO.foreachDiscard(1 to count)(n =>
        e.app.transact(tenant)(
          e.trail.record(
            AuditEvent(
              actor = Actor(s"user-$n", ActorKind.Person),
              action = "document.updated",
              target = AuditObject("document"),
              details = Jsonb("{}"),
              reason = None,
              channel = Channel.Ui,
              correlationId = None
            )
          )
        )
      )
    )

  /** Deletes chain entries as table owner with the append-only guard disabled (simulated attack). */
  private def cutTail(tenant: TenantId, fromSeq: Long): ZIO[Env, DbError, Unit] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(Db.sql {
        execute("alter table dms.audit_event disable trigger audit_event_guard")
        execute(s"delete from dms.audit_event where tenant_id = '${tenant.value}' and chain_seq >= $fromSeq")
        execute("alter table dms.audit_event enable trigger audit_event_guard")
      })
    )

  private def write(t: TenantId)  = ZIO.serviceWithZIO[Env](_.checkpoints.write(t))
  private def verify(t: TenantId) = ZIO.serviceWithZIO[Env](_.checkpoints.verify(t))

  def spec = suite("AuditCheckpoints")(
    test("a checkpoint is written once per chain head with compliance retention") {
      for
        env    <- ZIO.service[Env]
        t      <- newTenant
        _      <- record(3, t)
        first  <- write(t)
        again  <- write(t)
        info   <- env.store.head(f"audit-checkpoints/${t.value}/${3L}%020d", None)
        report <- verify(t)
      yield assertTrue(
        first.map(_.chainSeq) == Some(3L),
        again.isEmpty,
        info.retention.map(_.mode) == Some(RetentionMode.Compliance),
        report.ok
      )
    },
    test("cutting entries off the end of the chain is detected") {
      for
        t       <- newTenant
        _       <- record(3, t)
        _       <- write(t)
        _       <- record(2, t)
        _       <- write(t)
        _       <- cutTail(t, fromSeq = 4)
        chainOk <- ZIO.serviceWithZIO[Env](_.trail.verify(t))
        report  <- verify(t)
      yield assertTrue(
        chainOk.ok, // the links alone cannot show a cut tail
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((5L, Violation.Kind.CheckpointMismatch))
      )
    },
    test("a forged later version of a checkpoint is detected, and the original cannot be deleted") {
      for
        env <- ZIO.service[Env]
        t   <- newTenant
        _   <- record(2, t)
        _   <- write(t)
        key = f"audit-checkpoints/${t.value}/${2L}%020d"
        original <- env.store.versions(key).map(_.head)
        forged = AuditCheckpoints.render(Checkpoint(t, 2L, "0" * 64, java.time.Instant.now()))
        _ <- env.store.put(key, ObjectContent.Bytes(Chunk.fromArray(forged.getBytes(UTF_8))), "text/plain")
        delete <- env.store.delete(key, original.versionId).either
        report <- verify(t)
      yield assertTrue(
        delete.left.exists(_.isInstanceOf[StorageError.Locked]),
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((2L, Violation.Kind.CheckpointMismatch))
      )
    },
    test("checkpoint format round-trips and rejects unknown formats") {
      val cp = Checkpoint(TenantId.random(), 7L, "a" * 64, java.time.Instant.parse("2026-10-28T12:00:00Z"))
      assertTrue(
        AuditCheckpoints.parse(AuditCheckpoints.render(cp)) == Right(cp),
        AuditCheckpoints.parse(AuditCheckpoints.render(cp).replace("format=1", "format=9")).isLeft
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

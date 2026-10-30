package dms.workflow.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.queue.Outbox
import dms.db.{Db, DbConfig, Jsonb, Migrations}
import dms.kernel.TenantId
import dms.workflow.*
import org.flowable.common.engine.impl.cfg.TransactionState
import org.flowable.common.engine.impl.context.Context
import org.flowable.common.engine.impl.interceptor.{Command, CommandContext}
import org.flowable.engine.ProcessEngine
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

/** The embedded Flowable engine against a Testcontainers database (ADR 0006, 0024): schema `flowable` created
  * by the schema owner, engine calls as the application role inside `Db.transact`, generated BPMN models.
  *
  * Regression test of the transaction glue in [[SharedTransaction]]; it must pass on every Flowable upgrade.
  */
object SharedTransactionSpec extends ZIOSpecDefault:

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Env(app: Db, owner: Db, flowable: FlowableEngine, tenant: TenantId)

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
      _      <- FlowableEngine.migrateSchema(c.jdbcUrl, c.username, c.password)
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      tenant <- ZIO.succeed(TenantId.random())
      _      <- owner
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      appSource <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 4))
      flowable  <- (ZLayer.succeed(appSource) >>> FlowableEngine.layer).build.map(_.get)
    yield Env(Db(appSource), owner, flowable, tenant)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val parallel = WorkflowDefinition(
    List(
      Step(
        "review",
        "Review",
        StepKind.Review,
        List(Lane("department", "Reviewer"), Lane("compliance", "Reviewer"))
      ),
      Step(
        "legal",
        "Legal review",
        StepKind.Review,
        List(Lane("legal", "Reviewer")),
        StepCondition.ExtraReviewRequired
      ),
      Step("approval", "Approval", StepKind.Approval, List(Lane("approver", "Approver")))
    )
  )

  private def deploy(engine: ProcessEngine, tenant: TenantId, key: String, d: WorkflowDefinition): String =
    val repository = engine.getRepositoryService
    val deployment = repository
      .createDeployment()
      .name(key)
      .tenantId(tenant.value.toString)
      .addString(s"$key.bpmn20.xml", Bpmn.render(key, key, d))
      .enableDuplicateFiltering()
      .deploy()
    repository.createProcessDefinitionQuery().deploymentId(deployment.getId).singleResult().getId

  private def start(engine: ProcessEngine, definitionId: String, resumeAt: String, extra: Boolean): String =
    engine.getRuntimeService
      .createProcessInstanceBuilder()
      .processDefinitionId(definitionId)
      .variables(
        Map[String, AnyRef](Bpmn.ResumeAt -> resumeAt, Bpmn.ExtraReview -> Boolean.box(extra)).asJava
      )
      .start()
      .getId

  private def openTasks(engine: ProcessEngine, instance: String): List[String] =
    engine.getTaskService
      .createTaskQuery()
      .processInstanceId(instance)
      .list()
      .asScala
      .toList
      .map(_.getTaskDefinitionKey)
      .sorted

  private def outboxRows(e: Env, aggregate: UUID): Task[Long] =
    e.app
      .transact(e.tenant)(
        Db.sql(
          sql"select count(*) from dms.outbox_event where aggregate_id = $aggregate".query[Long].run().head
        )
      )
      .mapError(err => RuntimeException(err.toString))

  def spec = suite("Flowable in the shared transaction")(
    test("Flowable's tables live in schema flowable, without row-level security") {
      for
        e      <- ZIO.service[Env]
        result <- e.owner
          .transactSystem(
            Db.sql(
              sql"""select count(*), count(*) filter (where c.relrowsecurity)
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'flowable' and c.relkind = 'r'""".query[(Long, Long)].run().head
            )
          )
          .mapError(err => RuntimeException(err.toString))
      yield assertTrue(result._1 >= 30L, result._2 == 0L)
    },
    test("parallel lanes, a skipped conditional step and the end run through the generated model") {
      for
        e      <- ZIO.service[Env]
        result <- e.app
          .transact(e.tenant)(
            e.flowable.run { engine =>
              val id       = deploy(engine, e.tenant, "approval-flow", parallel)
              val instance = start(engine, id, "review", extra = false)
              val first    = openTasks(engine, instance)
              engine.getTaskService.createTaskQuery().processInstanceId(instance).list().asScala.foreach {
                t =>
                  engine.getTaskService.complete(t.getId)
              }
              val second = openTasks(engine, instance)
              engine.getTaskService.createTaskQuery().processInstanceId(instance).list().asScala.foreach {
                t =>
                  engine.getTaskService.complete(t.getId)
              }
              val ended =
                engine.getRuntimeService.createProcessInstanceQuery().processInstanceId(instance).count() == 0
              (first, second, ended)
            }
          )
          .mapError(err => RuntimeException(err.toString))
      yield assertTrue(
        result._1 == List("task.review.compliance", "task.review.department"),
        result._2 == List("task.approval.approver"),
        result._3
      )
    },
    test("the extra review step runs when required, and a round can resume at a later step") {
      for
        e      <- ZIO.service[Env]
        result <- e.app
          .transact(e.tenant)(
            e.flowable.run { engine =>
              val id    = deploy(engine, e.tenant, "approval-flow", parallel)
              val extra = start(engine, id, "legal", extra = true)
              val skip  = start(engine, id, "legal", extra = false)
              (openTasks(engine, extra), openTasks(engine, skip))
            }
          )
          .mapError(err => RuntimeException(err.toString))
      yield assertTrue(result._1 == List("task.legal.legal"), result._2 == List("task.approval.approver"))
    },
    test("an engine step and an own write roll back together when the transaction fails") {
      for
        e         <- ZIO.service[Env]
        aggregate <- ZIO.succeed(UUID.randomUUID())
        instance  <- e.app
          .transact(e.tenant)(
            e.flowable.run(engine =>
              start(engine, deploy(engine, e.tenant, "approval-tx", parallel), "review", false)
            )
          )
          .mapError(err => RuntimeException(err.toString))
        failed <- e.app
          .transact(e.tenant)(
            for
              _ <- Outbox
                .publish("workflow", aggregate, "test", Jsonb.empty)
                .mapError(err => RuntimeException(err.toString))
              _ <- e.flowable
                .run { engine =>
                  engine.getTaskService.createTaskQuery().processInstanceId(instance).list().asScala.foreach {
                    t =>
                      engine.getTaskService.complete(t.getId)
                  }
                }
                .mapError(err => RuntimeException(err.toString))
              _ <- ZIO.fail(RuntimeException("business rule violated"))
            yield ()
          )
          .either
        tasks <- e.app
          .transact(e.tenant)(e.flowable.run(openTasks(_, instance)))
          .mapError(err => RuntimeException(err.toString))
        rows <- outboxRows(e, aggregate)
      yield assertTrue(
        failed.isLeft,
        tasks == List("task.review.compliance", "task.review.department"),
        rows == 0L
      )
    },
    test("an engine failure comes back as a typed error; the failed command leaves no engine change") {
      for
        e         <- ZIO.service[Env]
        aggregate <- ZIO.succeed(UUID.randomUUID())
        result    <- e.app
          .transact(e.tenant)(
            for
              _ <- Outbox.publish("workflow", aggregate, "test", Jsonb.empty)
              r <- e.flowable.run(_.getTaskService.complete("no-such-task")).either
            yield r
          )
          .either
        rows <- outboxRows(e, aggregate)
      yield assertTrue(
        result.exists(_.left.exists(_.isInstanceOf[WorkflowError.NotFound])),
        rows == 1L // the typed error is returned; the caller decides (here: commit without engine change)
      )
    },
    test("a running instance stays on its process definition version when a new one is deployed") {
      for
        e      <- ZIO.service[Env]
        result <- e.app
          .transact(e.tenant)(
            e.flowable.run { engine =>
              val v1       = deploy(engine, e.tenant, "approval-versioned", WorkflowDefinition.default)
              val instance = start(engine, v1, "review", extra = false)
              val again    = deploy(engine, e.tenant, "approval-versioned", WorkflowDefinition.default)
              val v2       = deploy(engine, e.tenant, "approval-versioned", parallel)
              val running  = engine.getRuntimeService
                .createProcessInstanceQuery()
                .processInstanceId(instance)
                .singleResult()
                .getProcessDefinitionId
              (v1, again, v2, running, openTasks(engine, instance))
            }
          )
          .mapError(err => RuntimeException(err.toString))
      yield assertTrue(
        result._1 == result._2,
        result._1 != result._3,
        result._4 == result._1,
        result._5 == List("task.review.reviewer")
      )
    },
    test(
      "commit hook: the engine's COMMITTED listeners run after the outer commit, never on rollback (DMS-87)"
    ) {
      for
        e      <- ZIO.service[Env]
        events <- Ref.make(List.empty[String])
        record = (what: String) =>
          Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(events.update(_ :+ what)).getOrThrow())
        listen = (engine: ProcessEngine) =>
          engine.getManagementService.executeCommand(new Command[Unit]:
            def execute(context: CommandContext): Unit =
              val tx = Context.getTransactionContext
              tx.addTransactionListener(TransactionState.COMMITTING, _ => record("committing"))
              tx.addTransactionListener(TransactionState.COMMITTED, _ => record("committed"))
              tx.addTransactionListener(TransactionState.ROLLED_BACK, _ => record("rolled-back")))
        _ <- e.app
          .transact(e.tenant)(
            e.flowable.run(listen) *> events.get.flatMap(seen =>
              events.update(_ :+ s"before commit: ${seen.mkString(",")}")
            )
          )
          .mapError(err => RuntimeException(err.toString))
        afterCommit <- events.getAndSet(Nil)
        failed      <- e.app
          .transact(e.tenant)(
            e.flowable.run(listen) *> ZIO.fail(WorkflowError.Conflict("business rule failed"))
          )
          .either
        afterRollback <- events.getAndSet(Nil)
        engineFailed  <- e.app
          .transact(e.tenant)(
            e.flowable.run { engine =>
              listen(engine)
              throw IllegalStateException("engine call failed")
            }
          )
          .either
        afterEngineFailure <- events.get
      yield assertTrue(
        afterCommit == List("committing", "before commit: committing", "committed"),
        failed.isLeft,
        afterRollback == List("committing", "rolled-back"),
        engineFailed.isLeft,
        // a failed engine call rolls its listeners back at once; the transaction then rolls back as well
        afterEngineFailure.headOption.contains("rolled-back"),
        !afterEngineFailure.contains("committed")
      )
    },
    test("commit hooks of Db: after commit or after rollback, failures logged, not outside a transaction") {
      for
        e    <- ZIO.service[Env]
        seen <- Ref.make(List.empty[String])
        ok   <- e.app
          .transact(e.tenant)(
            Db.afterCommit(seen.update(_ :+ "commit-1")) *>
              Db.afterCommit(ZIO.die(RuntimeException("hook failed"))) *>
              Db.afterCommit(seen.update(_ :+ "commit-2")) *>
              Db.afterRollback(seen.update(_ :+ "rollback")) *> seen.get
          )
          .mapError(err => RuntimeException(err.toString))
        committed <- seen.getAndSet(Nil)
        failed    <- e.app
          .transact(e.tenant)(
            Db.afterCommit(seen.update(_ :+ "commit")) *> Db.afterRollback(seen.update(_ :+ "rollback")) *>
              ZIO.fail("boom")
          )
          .either
        rolledBack <- seen.get
        outside    <- Db.afterCommit(ZIO.unit).either
      yield assertTrue(
        ok.isEmpty,
        committed == List("commit-1", "commit-2"),
        failed.isLeft,
        rolledBack == List("rollback"),
        outside.isLeft
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

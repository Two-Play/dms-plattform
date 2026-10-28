package spike.flowable

import org.flowable.engine.ProcessEngine
import zio.*

import java.util.UUID
import javax.sql.DataSource

object Main extends ZIOAppDefault:
  val Tenant = "tenant-a"
  val Legal   = ("lena", List("reviewers-legal"))
  val Quality = ("quinn", List("reviewers-quality"))
  val Security = ("sam", List("reviewers-security"))
  val Approver = ("alex", List("approvers"))
  val Manager  = ("maria", List("approval-managers"))

  def log(msg: String): UIO[Unit] = Clock.instant.flatMap(t => Console.printLine(s"[$t] $msg").orDie)

  def sql[A](ds: DataSource)(q: String)(read: java.sql.ResultSet => A): Task[A] =
    ZIO.attemptBlocking {
      val c = ds.getConnection
      try { val rs = c.createStatement().executeQuery(q); try read(rs) finally rs.close() }
      finally c.close()
    }
  def count(ds: DataSource, q: String): Task[Long] = sql(ds)(q)(rs => { rs.next(); rs.getLong(1) })

  def flowableVersion: String = classOf[ProcessEngine].getPackage.getImplementationVersion

  def bootEngine(ds: DataSource, sharedTx: Boolean, label: String): Task[ProcessEngine] =
    for
      t0 <- Clock.nanoTime
      e  <- ZIO.attemptBlocking(Engine.build(ds, sharedTx))
      t1 <- Clock.nanoTime
      _  <- log(f"[$label] engine '${e.getName}' built in ${(t1 - t0) / 1e6}%.0f ms (sharedTx=$sharedTx)")
    yield e

  def tasksOf(wf: WorkflowService, who: (String, List[String])) = wf.listTasks(Tenant, who._1, who._2)

  def completeFirst(wf: WorkflowService, who: (String, List[String]), pid: String, vars: Map[String, Any] = Map.empty) =
    tasksOf(wf, who).map(_.filter(_.processInstanceId == pid)).flatMap {
      case t :: _ => log(s"  ${who._1} completes '${t.name}' (${t.id})") *> wf.completeTask(t.id, who._1, who._2, vars)
      case Nil    => ZIO.dieMessage(s"no task for ${who._1} in $pid")
    }

  // ---------------------------------------------------------------- Q3 + Q7: end-to-end incl. timer
  def endToEnd(wf: WorkflowService, engine: ProcessEngine) =
    for
      _   <- log("=== Q3/Q7: end-to-end run with boundary timer escalation")
      doc  = DocumentRef(UUID.randomUUID(), 3)
      p   <- wf.startReview(Tenant, doc, initiator = "author-anna", approvalTimeout = "PT3S")
      _   <- log(s"  started $p for $doc (businessKey=${doc.businessKey})")
      dt  <- wf.tasksForDocument(Tenant, doc)
      _   <- log(s"  tasks for document: ${dt.map(t => s"${t.taskKey}${t.candidateGroups.mkString("[", ",", "]")} doc=${t.document}")}")
      lt  <- tasksOf(wf, Legal)
      _   <- log(s"  lena (reviewers-legal) sees: ${lt.map(_.name)}")
      // typed error: wrong user
      err <- wf.completeTask(lt.head.id, Quality._1, Quality._2).flip
      _   <- log(s"  quinn tries to complete legal review -> typed error: $err")
      _   <- completeFirst(wf, Legal, p.processInstanceId)
      _   <- completeFirst(wf, Quality, p.processInstanceId)
      at  <- tasksOf(wf, Approver)
      _   <- log(s"  alex (approvers) sees: ${at.filter(_.processInstanceId == p.processInstanceId).map(_.name)}; timer jobs: " +
                 engine.getManagementService.createTimerJobQuery().processInstanceId(p.processInstanceId).list().size())
      due <- ZIO.attemptBlocking(engine.getManagementService.createTimerJobQuery().processInstanceId(p.processInstanceId).singleResult().getDuedate.toInstant)
      t0  <- Clock.instant
      _   <- log(s"  timer job due at $due")
      esc <- tasksOf(wf, Manager).map(_.filter(_.processInstanceId == p.processInstanceId))
               .repeat(Schedule.recurUntil[List[TaskView]](_.nonEmpty) && Schedule.spaced(100.millis) && Schedule.recurs(300))
               .map(_._1)
      t1  <- Clock.instant
      created <- ZIO.attemptBlocking(engine.getTaskService.createTaskQuery().taskId(esc.head.id).singleResult().getCreateTime.toInstant)
      _   <- log(s"  TIMER FIRED: escalation task ${esc.map(_.name)} for approval-managers created at $created " +
                 s"(${java.time.Duration.between(due, created).toMillis} ms after due date; seen after ${java.time.Duration.between(t0, t1).toMillis} ms of polling)")
      gone <- tasksOf(wf, Approver).map(_.exists(_.processInstanceId == p.processInstanceId))
      _   <- log(s"  original approval task still open: $gone")
      _   <- completeFirst(wf, Manager, p.processInstanceId, Map("approved" -> true))
      hpi <- ZIO.attemptBlocking(engine.getHistoryService.createHistoricProcessInstanceQuery().processInstanceId(p.processInstanceId).singleResult())
      acts <- ZIO.attemptBlocking(engine.getHistoryService.createHistoricActivityInstanceQuery().processInstanceId(p.processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc().list())
      _   <- log(s"  process ended: ${hpi.getEndTime != null}, endActivity=${hpi.getEndActivityId}, startUser=${hpi.getStartUserId}")
      _   <- log(s"  activity history: ${scala.jdk.CollectionConverters.ListHasAsScala(acts).asScala.map(_.getActivityId).mkString(" -> ")}")
    yield ()

  // ---------------------------------------------------------------- Q6: versioning
  def versioning(wf: WorkflowService, engine: ProcessEngine) =
    for
      _  <- log("=== Q6: deploy v2 while a v1 instance runs")
      d1 <- DocumentRef(UUID.randomUUID(), 1).pipe(ZIO.succeed(_))
      p1 <- wf.startReview(Tenant, d1, "author-anna", "PT1H")
      _  <- log(s"  v1 instance: $p1")
      _  <- wf.deploy(Tenant, "processes/document-review-v2.bpmn20.xml")
      d2 = DocumentRef(UUID.randomUUID(), 1)
      p2 <- wf.startReview(Tenant, d2, "author-anna", "PT1H")
      _  <- log(s"  new instance after v2 deploy: $p2")
      t1 <- wf.tasksForDocument(Tenant, d1)
      t2 <- wf.tasksForDocument(Tenant, d2)
      _  <- log(s"  v1 instance tasks: ${t1.map(_.taskKey).sorted}; v2 instance tasks: ${t2.map(_.taskKey).sorted}")
      _  <- completeFirst(wf, Legal, p1.processInstanceId) *> completeFirst(wf, Quality, p1.processInstanceId)
      a1 <- wf.tasksForDocument(Tenant, d1)
      _  <- log(s"  v1 instance after 2 reviews (no security review required): ${a1.map(_.taskKey)}")
      pd <- ZIO.attemptBlocking(engine.getRuntimeService.createProcessInstanceQuery().processInstanceId(p1.processInstanceId).singleResult())
      _  <- log(s"  v1 instance still bound to definition ${pd.getProcessDefinitionId} (version ${pd.getProcessDefinitionVersion})")
    yield ()

  extension [A](a: A) def pipe[B](f: A => B): B = f(a)

  // ---------------------------------------------------------------- Q5: transactions
  def insertDecision(doc: DocumentRef, decision: String): Task[Unit] =
    Tx.withConnection { c =>
      val ps = c.prepareStatement("insert into public.review_decision(document_id, version, decision) values (?,?,?)")
      ps.setObject(1, doc.documentId); ps.setInt(2, doc.version); ps.setString(3, decision); ps.executeUpdate(); ()
    }

  def txScenarios(ds: DataSource, wf: WorkflowService) =
    def decisions(doc: DocumentRef) = count(ds, s"select count(*) from public.review_decision where document_id = '${doc.documentId}'")
    def openTasks(doc: DocumentRef) = wf.tasksForDocument(Tenant, doc).map(_.map(_.taskKey).sorted)
    for
      _   <- log("=== Q5: shared transaction (own JDBC write + Flowable completeTask)")
      doc  = DocumentRef(UUID.randomUUID(), 1)
      p   <- wf.startReview(Tenant, doc, "author-anna", "PT1H")
      legal <- tasksOf(wf, Legal).map(_.filter(_.processInstanceId == p.processInstanceId).head)
      // (a) business failure after Flowable call -> both roll back
      r1  <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> wf.completeTask(legal.id, Legal._1, Legal._2) *> ZIO.fail("business rule violated")).either
      n1  <- decisions(doc); o1 <- openTasks(doc)
      _   <- log(s"  (a) write + completeTask + business failure -> $r1; rows=$n1, open tasks=$o1")
      // (b) Flowable failure -> own write rolls back
      r2  <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> wf.completeTask("no-such-task", Legal._1, Legal._2)).either
      n2  <- decisions(doc)
      _   <- log(s"  (b) write + completeTask(unknown id) -> $r2; rows=$n2")
      // (c) Flowable engine exception inside the command (unknown expression) -> rollback-only, own write rolls back
      r3  <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> Tx.blocking(wfEngine.getRuntimeService.setVariable("no-such-execution", "x", 1))).either
      n3  <- decisions(doc)
      _   <- log(s"  (c) write + Flowable FlowableObjectNotFoundException -> ${r3.left.map(_.toString.take(120))}; rows=$n3")
      // (e) Flowable exception swallowed by the caller -> context marked rollback-only, commit refused
      r5  <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> Tx.blocking(wfEngine.getRuntimeService.setVariable("no-such-execution", "x", 1)).ignore).either
      n5  <- decisions(doc)
      _   <- log(s"  (e) write + swallowed Flowable exception -> $r5; rows=$n5")
      // (d) happy path -> both commit
      r4  <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> wf.completeTask(legal.id, Legal._1, Legal._2)).either
      n4  <- decisions(doc); o4 <- openTasks(doc)
      _   <- log(s"  (d) write + completeTask -> $r4; rows=$n4, open tasks=$o4")
    yield ()

  @volatile var wfEngine: ProcessEngine = null

  // Same experiment with the default standalone configuration (each Flowable command commits on its own).
  def plainEngineScenario(ds: DataSource, plain: ProcessEngine) =
    val wf = FlowableWorkflowService(plain)
    for
      _   <- log("=== Q5 baseline: default StandaloneProcessEngineConfiguration (no shared tx)")
      doc  = DocumentRef(UUID.randomUUID(), 1)
      p   <- wf.startReview(Tenant, doc, "author-anna", "PT1H")
      legal <- wf.listTasks(Tenant, Legal._1, Legal._2).map(_.filter(_.processInstanceId == p.processInstanceId).head)
      r   <- Tx.transact(ds)(insertDecision(doc, "legal-ok") *> wf.completeTask(legal.id, Legal._1, Legal._2) *> ZIO.fail("business rule violated")).either
      n   <- count(ds, s"select count(*) from public.review_decision where document_id = '${doc.documentId}'")
      o   <- wf.tasksForDocument(Tenant, doc).map(_.map(_.taskKey).sorted)
      _   <- log(s"  write + completeTask + business failure -> $r; rows=$n, open tasks=$o  <-- INCONSISTENT: task completed, row rolled back")
    yield ()

  def run =
    val program = ZIO.scoped {
      for
        ds <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(Engine.dataSource()))
        _  <- log(s"Flowable version on classpath: $flowableVersion, Java ${java.lang.System.getProperty("java.version")}")
        _  <- ZIO.attemptBlocking {
                val c = ds.getConnection
                try
                  c.createStatement().execute("create schema if not exists flowable")
                  c.createStatement().execute(
                    "create table if not exists public.review_decision(id bigserial primary key, document_id uuid not null, version int not null, decision text not null)"
                  )
                finally c.close()
              }
        before <- count(ds, "select count(*) from information_schema.tables where table_schema = 'flowable'")
        e1 <- bootEngine(ds, sharedTx = true, "boot #1")
        after <- count(ds, "select count(*) from information_schema.tables where table_schema = 'flowable'")
        props <- sql(ds)("select name_, value_ from flowable.act_ge_property where name_ like '%schema.version' order by 1")(rs =>
                   Iterator.continually(rs).takeWhile(_.next()).map(r => s"${r.getString(1)}=${r.getString(2)}").toList)
        _  <- log(s"  tables in schema 'flowable': before=$before after=$after; $props")
        _  <- ZIO.attemptBlocking(e1.close())
        e  <- bootEngine(ds, sharedTx = true, "boot #2 (schema exists)")
        _  <- ZIO.addFinalizer(ZIO.attemptBlocking(e.close()).orDie)
        _   = { wfEngine = e }
        wf  = FlowableWorkflowService(e)
        _  <- wf.deploy(Tenant, "processes/document-review-v1.bpmn20.xml").flatMap(id => log(s"deployed v1: $id"))
        dup <- wf.deploy(Tenant, "processes/document-review-v1.bpmn20.xml")
        _  <- log(s"re-deploy of identical v1 (duplicate filtering) returns existing deployment: $dup")
        _  <- endToEnd(wf, e)
        _  <- txScenarios(ds, wf)
        _  <- versioning(wf, e)
        _  <- ZIO.succeed(java.lang.System.gc())
        rt0 = java.lang.Runtime.getRuntime
        _  <- log(s"heap used after GC with running engine: ${(rt0.totalMemory - rt0.freeMemory) / 1024 / 1024} MB; JVM threads: ${Thread.getAllStackTraces.keySet.size}")
        _  <- ZIO.attemptBlocking(e.close())
        plain <- ZIO.attemptBlocking(Engine.build(ds, sharedTx = false, asyncExecutor = false))
        _  <- plainEngineScenario(ds, plain)
        _  <- ZIO.attemptBlocking(plain.close())
        _  <- log("DONE")
      yield ()
    }
    program.tapErrorCause(c => ZIO.logErrorCause("spike failed", c))

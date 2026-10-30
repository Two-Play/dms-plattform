package dms.notifications.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.notifications.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** Notification delivery against a Testcontainers database (DMS-130, ADR 0052): the outbox subscriber's
  * idempotent fan-out, in-app delivery exactly once, the read check at delivery time (no title for people who
  * may not read the document), preferences (email per category, language), email retries with backoff until
  * the dead letter, sending a dead delivery again, missing addresses, an unreachable directory, row-level
  * security of the new tables and the clean-up. Events, read check, directory and mail are test doubles; the
  * job runner is replaced by claiming jobs and calling the handlers.
  */
object NotificationJobsSpec extends ZIOSpecDefault:

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val anna  = UserId("anna")
  private val ben   = UserId("ben")
  private val clara = UserId("clara")
  private val doc   = DocumentId(UUID.randomUUID())

  /** `test.notice` events name their recipients and kind in the payload. */
  private object Events extends NotificationEvents:
    def concerns(event: OutboxEvent): Boolean                  = event.eventType == "test.notice"
    def describe(event: OutboxEvent): IO[String, List[Notice]] =
      ZIO.fromEither(event.payload.value.fromJson[Json.Obj]).map { p =>
        val recipients = p.get("recipients").collect { case Json.Arr(xs) =>
          xs.toList.collect { case Json.Str(s) => UserId(s) }
        }
        val category =
          p.get("category").collect { case Json.Str(c) => c }.flatMap(NotificationCategory.fromKey)
        List(
          Notice(
            p.get("kind").collect { case Json.Str(k) => k }.getOrElse("acknowledgement.requested"),
            category.getOrElse(NotificationCategory.Acknowledgement),
            recipients.getOrElse(Nil),
            Some(DocumentRef(doc, None)),
            Map("version" -> "2.0", "step" -> "Legal review", "stepKind" -> "Review", "returnedBy" -> "ben")
          )
        )
      }

  final case class Env(
      app: Db,
      owner: Db,
      tenant: TenantId,
      readers: Ref[Set[UserId]],
      mails: Ref[List[Mail]],
      mailUp: Ref[Boolean],
      directoryUp: Ref[Boolean],
      jobs: NotificationJobs
  )

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
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      tenant <- ZIO.succeed(TenantId.random())
      _      <- owner
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      app         <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 3)).map(Db(_))
      readers     <- Ref.make(Set(anna, ben))
      mails       <- Ref.make(List.empty[Mail])
      mailUp      <- Ref.make(true)
      directoryUp <- Ref.make(true)
      documents = new NotificationDocuments:
        def visibleTo(t: TenantId, user: UserId, ref: DocumentRef) =
          readers.get.map(r =>
            Option.when(r.contains(user))(DocumentFacts("Travel policy", Some("2.0"), Some("POL-7")))
          )
      directory = new RecipientDirectory:
        def people(t: TenantId, users: Set[UserId]) =
          directoryUp.get.flatMap(up =>
            if !up then ZIO.fail("ZITADEL is not reachable")
            else
              ZIO.succeed(
                Map(
                  anna  -> Person(Some("Anna Albers"), Some("anna@example.org")),
                  ben   -> Person(Some("Ben Becker"), Some("ben@example.org")),
                  clara -> Person(Some("Clara Conrad"), None)
                ).filter((u, _) => users.contains(u))
              )
          )
      sender = new MailSender:
        def send(mail: Mail) =
          mailUp.get.flatMap(up => if up then mails.update(_ :+ mail) else ZIO.fail("connection refused"))
      jobs = NotificationJobs(
        app,
        Events,
        documents,
        directory,
        sender,
        DeliverySettings("https://dms.example.org/", emailEnabled = true, maxAttempts = 3)
      )
    yield Env(app, owner, tenant, readers, mails, mailUp, directoryUp, jobs)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val ids = Unsafe.unsafe(implicit u => Ref.unsafe.make(1000L))

  private def event(e: Env, recipients: List[UserId], kind: String = "acknowledgement.requested") =
    ids
      .updateAndGet(_ + 1)
      .map(id =>
        OutboxEvent(
          id,
          e.tenant,
          "document",
          doc.value,
          "test.notice",
          Jsonb(
            Json
              .Obj(
                "recipients" -> Json.Arr(recipients.map(r => Json.Str(r.value))*),
                "kind"       -> Json.Str(kind)
              )
              .toJson
          ),
          None,
          1
        )
      )

  private def tx[A](e: Env)(work: IO[DbError, A]): Task[A] =
    e.app.transact(e.tenant)(work).mapError(err => RuntimeException(err.toString))

  /** Claims the due delivery jobs and runs them like the job runner: completes or fails them with no delay.
    */
  private def runJobs(e: Env): Task[List[Either[String, Unit]]] =
    JobQueue
      .claim(e.app, "test-worker", Set(NotificationJobs.DeliverKind), 100, 1.minute)
      .mapError(err => RuntimeException(err.toString))
      .flatMap(ZIO.foreach(_)(job => run(e, job)))

  private def run(e: Env, job: ClaimedJob): Task[Either[String, Unit]] =
    e.jobs.handlers(job.kind)(job).either.tap {
      case Right(_) =>
        JobQueue.complete(e.app, job.id, "test-worker").mapError(err => RuntimeException(err.toString))
      case Left(error) =>
        JobQueue
          .fail(e.app, job.id, "test-worker", error, Duration.Zero)
          .mapError(err => RuntimeException(err.toString))
    }

  private def deliveries(e: Env, eventId: Long) = tx(e)(NotificationStore.deliveriesOfEvent(eventId))

  def spec = suite("NotificationJobs")(
    test("an event is fanned out once per recipient and channel, also when the relay delivers it again") {
      for
        e      <- ZIO.service[Env]
        ev     <- event(e, List(anna, ben, anna))
        _      <- e.jobs.deliver(ev)
        _      <- e.jobs.deliver(ev)
        ds     <- deliveries(e, ev.id)
        queued <- e.owner
          .transactSystem(
            Db.sql(
              sql"""select count(*)::int from dms.job where kind = ${NotificationJobs.DeliverKind}
                    and payload->>'deliveryId' in (select id::text from dms.notification_delivery
                                                    where event_id = ${ev.id})""".query[Int].run().head
            )
          )
          .mapError(err => RuntimeException(err.toString))
        _ <- runJobs(e)
      yield assertTrue(
        ds.size == 4,
        ds.map(d => (d.recipient, d.channel)).toSet == Set(
          anna -> Channel.InApp,
          anna -> Channel.Email,
          ben  -> Channel.InApp,
          ben  -> Channel.Email
        ),
        queued == 4
      )
    },
    test("in-app notifications are stored once, rendered after the read check at delivery time") {
      for
        e  <- ZIO.service[Env]
        _  <- e.readers.set(Set(anna)) // ben lost access after the event was written
        ev <- event(e, List(anna, ben))
        _  <- e.jobs.deliver(ev)
        _  <- runJobs(e)
        ds <- deliveries(e, ev.id)
        inApp = ds.filter(_.channel == Channel.InApp)
        // a repeated job (e.g. after a lost lease) stores nothing new
        _ <- ZIO.foreachDiscard(inApp)(d =>
          e.jobs.handlers(NotificationJobs.DeliverKind)(
            ClaimedJob(
              0,
              Some(e.tenant),
              NotificationJobs.DeliverKind,
              Jsonb(s"""{"deliveryId":"${d.id}"}"""),
              2,
              3
            )
          )
        )
        annas <- tx(e)(NotificationStore.page(anna, unreadOnly = false, 0, 50))
        bens  <- tx(e)(NotificationStore.page(ben, unreadOnly = false, 0, 50))
        mails <- e.mails.get
        _     <- e.readers.set(Set(anna, ben))
        annaN   = annas.filter(n => inApp.exists(_.id == n.id))
        benN    = bens.filter(n => inApp.exists(_.id == n.id))
        benMail = mails.filter(_.to == "ben@example.org").lastOption
      yield assertTrue(
        inApp.forall(_.status == Delivery.Delivered),
        annaN.size == 1,
        benN.size == 1,
        annaN.head.subject.contains("Travel policy"),
        annaN.head.body.contains("„Travel policy“ (Version 2.0)"),
        annaN.head.link.contains(s"/documents/${doc.value}"),
        !benN.head.subject.contains("Travel policy"),
        !benN.head.body.contains("Travel policy"),
        benN.head.body.contains("Bitte lesen Sie ein Dokument"),
        benMail.exists(m => !m.text.contains("Travel policy") && !m.subject.contains("Travel policy"))
      )
    },
    test("preferences: email off for a category records no email; the chosen language is used") {
      for
        e <- ZIO.service[Env]
        _ <- tx(e)(
          NotificationStore.savePreferences(
            ben,
            Some(Language.En),
            Map(NotificationCategory.Workflow -> false)
          )
        )
        before <- e.mails.get.map(_.size)
        ev     <- event(e, List(anna, ben), kind = "workflow.changes-requested")
        withWorkflow = ev.copy(payload =
          Jsonb(
            ev.payload.value
              .fromJson[Json.Obj]
              .toOption
              .get
              .merge(Json.Obj("category" -> Json.Str("workflow")))
              .toJson
          )
        )
        _     <- e.jobs.deliver(withWorkflow)
        _     <- runJobs(e)
        ds    <- deliveries(e, ev.id)
        mails <- e.mails.get.map(_.drop(before))
        bens  <- tx(e)(NotificationStore.page(ben, unreadOnly = true, 0, 1))
        // switching email off after the delivery was recorded still stops it: checked again at delivery
        _ <- tx(e)(
          NotificationStore.savePreferences(anna, None, Map(NotificationCategory.Acknowledgement -> false))
        )
        ev2 <- event(e, List(anna))
        _   <- tx(e)(NotificationStore.savePreferences(anna, None, Map.empty)) // email on while fanning out
        _   <- e.jobs.deliver(ev2)
        _   <- tx(e)(
          NotificationStore.savePreferences(anna, None, Map(NotificationCategory.Acknowledgement -> false))
        )
        _   <- runJobs(e)
        ds2 <- deliveries(e, ev2.id)
        _   <- tx(e)(NotificationStore.savePreferences(anna, None, Map.empty))
        _   <- tx(e)(NotificationStore.savePreferences(ben, None, Map.empty))
      yield assertTrue(
        ds.filter(_.recipient == ben).map(_.channel) == List(Channel.InApp),
        mails.map(_.to) == List("anna@example.org"),
        mails.head.language == Language.De,
        mails.head.subject == "[DMS] Änderungen erbeten: Travel policy (2.0)",
        mails.head.text.contains("Hallo Anna Albers,"),
        mails.head.text.contains(
          "Ben Becker hat im Schritt „Legal review“ Änderungen an „Travel policy“ (Version 2.0) erbeten."
        ),
        mails.head.text.contains(s"Öffnen: https://dms.example.org/documents/${doc.value}"),
        bens.head.subject == "Changes requested: Travel policy (2.0)",
        ds2
          .find(_.channel == Channel.Email)
          .exists(d => d.status == Delivery.Skipped && d.lastError.exists(_.contains("switched off")))
      )
    },
    test("a failing email is retried until it is dead, then it can be sent again") {
      for
        e      <- ZIO.service[Env]
        _      <- e.mailUp.set(false)
        ev     <- event(e, List(anna))
        _      <- e.jobs.deliver(ev)
        first  <- runJobs(e)
        after1 <- deliveries(e, ev.id).map(_.find(_.channel == Channel.Email).get)
        _      <- runJobs(e)
        _      <- runJobs(e)
        dead   <- deliveries(e, ev.id).map(_.find(_.channel == Channel.Email).get)
        listed <- tx(e)(NotificationStore.dead(10))
        _      <- e.mailUp.set(true)
        again  <- tx(e)(e.jobs.redeliver(dead.id))
        twice  <- tx(e)(e.jobs.redeliver(dead.id))
        _      <- runJobs(e)
        sent   <- deliveries(e, ev.id).map(_.find(_.channel == Channel.Email).get)
        mails  <- e.mails.get
      yield assertTrue(
        first.exists(_.isLeft),
        after1.status == Delivery.Retrying,
        after1.lastError.exists(_.contains("connection refused")),
        dead.status == Delivery.Dead,
        dead.attempts == 3,
        listed.exists(_.id == dead.id),
        again,
        !twice,
        sent.status == Delivery.Delivered,
        mails.count(_.messageId == s"<${dead.id}@notifications.dms>") == 1
      )
    },
    test("without an address the email is skipped; an unreachable directory is retried") {
      for
        e      <- ZIO.service[Env]
        ev     <- event(e, List(clara))
        _      <- e.jobs.deliver(ev)
        _      <- runJobs(e)
        clara1 <- deliveries(e, ev.id)
        _      <- e.directoryUp.set(false)
        ev2    <- event(e, List(anna))
        _      <- e.jobs.deliver(ev2)
        _      <- runJobs(e)
        down   <- deliveries(e, ev2.id)
        _      <- e.directoryUp.set(true)
        _      <- runJobs(e)
        up     <- deliveries(e, ev2.id)
      yield assertTrue(
        clara1.find(_.channel == Channel.Email).exists(d => d.status == Delivery.Skipped),
        clara1.find(_.channel == Channel.InApp).exists(_.status == Delivery.Delivered),
        // in-app does not need the directory: the ids stay where names are missing
        down.find(_.channel == Channel.InApp).exists(_.status == Delivery.Delivered),
        down.find(_.channel == Channel.Email).exists(_.status == Delivery.Retrying),
        up.find(_.channel == Channel.Email).exists(_.status == Delivery.Delivered)
      )
    },
    test("the inbox: paging, unread count, marking read; only the user's own notifications") {
      for
        e          <- ZIO.service[Env]
        evs        <- ZIO.foreach(1 to 3)(_ => event(e, List(clara)))
        _          <- ZIO.foreachDiscard(evs)(e.jobs.deliver)
        _          <- runJobs(e)
        page1      <- tx(e)(NotificationInbox.page(clara, unreadOnly = false, 0, 2))
        page2      <- tx(e)(NotificationInbox.page(clara, unreadOnly = false, 2, 2))
        foreign    <- tx(e)(NotificationInbox.markRead(anna, page1.items.head.id))
        own        <- tx(e)(NotificationInbox.markRead(clara, page1.items.head.id))
        unread     <- tx(e)(NotificationInbox.unreadCount(clara))
        all        <- tx(e)(NotificationInbox.markAllRead(clara))
        none       <- tx(e)(NotificationInbox.unreadCount(clara))
        onlyUnread <- tx(e)(NotificationInbox.page(clara, unreadOnly = true, 0, 10))
      yield assertTrue(
        page1.items.size == 2,
        page1.total == page2.total,
        page1.items.map(_.createdAt).sorted.reverse == page1.items.map(_.createdAt),
        (page1.items ++ page2.items).map(_.id).distinct.size == page1.total,
        !foreign,
        own,
        unread == page1.unread - 1,
        all == unread,
        none == 0,
        onlyUnread.items.isEmpty
      )
    },
    test("preferences have defaults per category and keep categories left out") {
      for
        e <- ZIO.service[Env]
        user = UserId("dora")
        initial <- tx(e)(NotificationInbox.preferences(user))
        changed <- tx(e)(
          NotificationInbox.changePreferences(
            user,
            Some(Language.En),
            Map(NotificationCategory.Deputyship -> false)
          )
        )
        again <- tx(e)(
          NotificationInbox.changePreferences(user, None, Map(NotificationCategory.LegalHold -> true))
        )
        _ <- e.owner
          .transactSystem(
            Db.sql(sql"update dms.tenant set language = 'en' where id = ${e.tenant.value}".update.run())
          )
          .mapError(err => RuntimeException(err.toString))
        tenantEn <- tx(e)(NotificationInbox.preferences(user))
        _        <- e.owner
          .transactSystem(
            Db.sql(sql"update dms.tenant set language = 'de' where id = ${e.tenant.value}".update.run())
          )
          .mapError(err => RuntimeException(err.toString))
      yield assertTrue(
        initial.language.isEmpty,
        initial.effectiveLanguage == Language.De,
        NotificationCategory.values.forall(c => initial.emailFor(c) == c.emailByDefault),
        changed.language.contains(Language.En),
        !changed.emailFor(NotificationCategory.Deputyship),
        again.language.isEmpty,
        !again.emailFor(NotificationCategory.Deputyship),
        again.emailFor(NotificationCategory.LegalHold),
        tenantEn.effectiveLanguage == Language.En
      )
    },
    test("row-level security: another tenant sees no notifications, deliveries or preferences") {
      for
        e     <- ZIO.service[Env]
        other <- ZIO.succeed(TenantId.random())
        _     <- e.owner
          .transactSystem(
            Db.sql(sql"insert into dms.tenant (id, name) values (${other.value}, ${"Other"})".update.run())
          )
          .mapError(err => RuntimeException(err.toString))
        rows <- e.app
          .transact(other)(
            Db.sql(
              (
                sql"select count(*)::int from dms.notification".query[Int].run().head,
                sql"select count(*)::int from dms.notification_delivery".query[Int].run().head,
                sql"select count(*)::int from dms.notification_settings".query[Int].run().head
              )
            )
          )
          .mapError(err => RuntimeException(err.toString))
        mine <- tx(e)(Db.sql(sql"select count(*)::int from dms.notification".query[Int].run().head))
        // the subject of a stored notification cannot be changed afterwards, only read_at
        changed <- e.app
          .transact(e.tenant)(Db.sql(sql"update dms.notification set subject = 'x'".update.run()))
          .either
      yield assertTrue(rows == (0, 0, 0), mine > 0, changed.isLeft)
    },
    test("the clean-up deletes read notifications and finished deliveries past the retention") {
      for
        e      <- ZIO.service[Env]
        before <- tx(e)(
          Db.sql(
            sql"select count(*)::int from dms.notification where read_at is not null".query[Int].run().head
          )
        )
        purged <- tx(e)(NotificationStore.purge(java.time.Instant.now().plusSeconds(60)))
        unread <- tx(e)(
          Db.sql(sql"select count(*)::int from dms.notification where read_at is null".query[Int].run().head)
        )
        after <- tx(e)(
          Db.sql(
            sql"select count(*)::int from dms.notification where read_at is not null".query[Int].run().head
          )
        )
        dead <- tx(e)(
          Db.sql(
            sql"select count(*)::int from dms.notification_delivery where status <> 'delivered' and status <> 'skipped'"
              .query[Int]
              .run()
              .head
          )
        )
      yield assertTrue(before > 0, purged._1 == before, purged._2 > 0, after == 0, unread > 0, dead >= 0)
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock

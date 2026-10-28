package spike.magnum

import com.augustnagro.magnum.*
import spike.common.*
import zio.*
import zio.test.*

import java.util.UUID

object MagnumSpec extends ZIOSpecDefault:
  import MagnumDb.inTx

  private def uniq(prefix: String) = s"$prefix-${UUID.randomUUID()}"

  def spec = suite("Magnum 1.3.1 + hand-written ZIO wrapper")(
    suite("1 tenant context + RLS")(
      test("tenant A cannot see tenant B rows") {
        for
          db <- ZIO.service[MagnumDb]
          a = TenantId.random(); b = TenantId.random()
          _  <- db.transact(Some(a))(inTx(DocumentRepo.insert(a, "a-1", Jsonb("{}"))))
          _  <- db.transact(Some(b))(inTx { DocumentRepo.insert(b, "b-1", Jsonb("{}")); DocumentRepo.insert(b, "b-2", Jsonb("{}")) })
          seenByA <- db.transact(Some(a))(inTx(DocumentRepo.findAll()))
          seenByB <- db.transact(Some(b))(inTx(DocumentRepo.findAll()))
        yield assertTrue(seenByA.map(_.title) == Vector("a-1"), seenByB.map(_.title).toSet == Set("b-1", "b-2"))
      },
      test("inserting a row for another tenant is rejected by the policy") {
        for
          db <- ZIO.service[MagnumDb]
          a = TenantId.random(); b = TenantId.random()
          err <- db.transact(Some(a))(inTx(DocumentRepo.insert(b, "smuggled", Jsonb("{}")))).flip
        yield assertTrue(err match
          case DbError.PermissionDenied(m) => m.contains("new row violates row-level security policy")
          case _                           => false)
      },
      test("no tenant set: query fails instead of returning rows") {
        for
          ds  <- ZIO.scoped(ZIO.fromAutoCloseable(ZIO.succeed(Pools.make("spike_db_access_app", "spike", 1))).flatMap { ds =>
                   MagnumDb(ds).transact(None)(inTx(DocumentRepo.findAll())).flip
                 })
        yield assertTrue(ds match
          case DbError.Other("42704", m) => m.contains("unrecognized configuration parameter \"app.tenant_id\"")
          case _                         => false)
      },
      test("SET LOCAL does not leak to the next transaction on the same pooled connection") {
        ZIO.scoped {
          for
            ds <- ZIO.fromAutoCloseable(ZIO.succeed(Pools.make("spike_db_access_app", "spike", 1)))
            db = MagnumDb(ds)
            a  = TenantId.random()
            first <- db.transact(Some(a))(inTx(sql"select pg_backend_pid(), current_setting('app.tenant_id', true)".query[(Int, String)].run().head))
            next  <- db.transact(None)(inTx(sql"select pg_backend_pid(), current_setting('app.tenant_id', true)".query[(Int, String)].run().head))
            // after reset the GUC exists but is '' -> the policy cast fails (fail closed)
            err   <- db.transact(None)(inTx(DocumentRepo.findAll())).flip
          yield assertTrue(
            first._1 == next._1,                // same physical connection
            first._2 == a.value.toString,
            next._2 == "",                      // reset at commit
            err match { case DbError.Other("22P02", m) => m.contains("invalid input syntax for type uuid"); case _ => false }
          )
        }
      },
      test("SET LOCAL with a bind parameter is not possible (documents why set_config is used)") {
        for
          db  <- ZIO.service[MagnumDb]
          t   = TenantId.random().value.toString
          err <- db.transact(None)(inTx(sql"set local app.tenant_id = $t".update.run())).flip
        yield assertTrue(err match { case DbError.Other("42601", m) => m.contains("syntax error at or near \"$1\""); case _ => false })
      }
    ),
    test("2 FOR UPDATE SKIP LOCKED: two concurrent fibers, no double processing") {
      val batch = uniq("batch")
      def worker(db: MagnumDb, name: String): IO[DbError, List[Long]] =
        db.transact(None)(inTx(JobRepo.claimNext(batch, name)).tap(_ => ZIO.sleep(5.millis))).flatMap {
          case Some(job) => worker(db, name).map(job.id :: _)
          case None      => ZIO.succeed(Nil)
        }
      for
        db    <- ZIO.service[MagnumDb]
        ids   <- db.transact(None)(inTx((1 to 40).map(i => JobRepo.enqueue(batch, Jsonb(s"""{"n":$i}"""))).toSet))
        res   <- worker(db, "w1").zipPar(worker(db, "w2"))
        (w1, w2) = res
        all = w1 ++ w2
      yield assertTrue(all.size == 40, all.toSet == ids, w1.nonEmpty, w2.nonEmpty)
    },
    test("3 JSONB write/read and WHERE payload->>'x' = ?") {
      val t = TenantId.random()
      for
        db  <- ZIO.service[MagnumDb]
        _   <- db.transact(Some(t))(inTx {
                 DocumentRepo.insert(t, "d1", Jsonb("""{"x":"invoice","n":1}"""))
                 DocumentRepo.insert(t, "d2", Jsonb("""{"x":"contract"}"""))
                 DocumentRepo.insert(t, "d3", Jsonb("""{"x":"invoice","nested":{"a":[1,2]}}"""))
               })
        hit <- db.transact(Some(t))(inTx(DocumentRepo.byMetadata("x", "invoice")))
      yield assertTrue(hit.map(_.title) == Vector("d1", "d3"), hit.head.metadata.value == """{"n": 1, "x": "invoice"}""")
    },
    suite("4 append-only audit")(
      test("insert ok, UPDATE rejected by trigger, DELETE by missing privilege – both typed failures") {
        val t = TenantId.random()
        for
          db   <- ZIO.service[MagnumDb]
          seq  <- db.transact(None)(inTx(AuditRepo.append(t, "document.created", Jsonb("""{"by":"x"}"""))))
          upd  <- db.transact(None)(inTx(AuditRepo.rewrite(seq, "tampered"))).flip
          del  <- db.transact(None)(inTx(AuditRepo.delete(seq))).flip
          left <- ZIO.serviceWithZIO[AdminDs](_.count("select count(*) from audit_event where seq = ? and action = 'document.created'", seq)).orDie
        yield assertTrue(
          upd match { case DbError.AppendOnlyViolation(m) => m.startsWith("ERROR: spike.audit_event is append-only: UPDATE is not allowed"); case _ => false },
          del == DbError.PermissionDenied("ERROR: permission denied for table audit_event"),
          left == 1L
        )
      }
    ),
    suite("5 sharing the JDBC connection with another component (Flowable)")(
      test("callback runs on the same connection inside the transaction; rollback covers both") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          db  <- ZIO.service[MagnumDb]
          seenTenant <- Ref.make("")
          exit <- db.transact(Some(t))(
                    for
                      _ <- inTx(DocumentRepo.insert(t, note, Jsonb("{}")))
                      _ <- MagnumDb.withConnection { con => // what Flowable would receive
                             val rs = con.createStatement().executeQuery("select current_setting('app.tenant_id')")
                             rs.next()
                             val ps = con.prepareStatement("insert into engine_record (note) values (?)")
                             ps.setString(1, note); ps.executeUpdate()
                             rs.getString(1)
                           }.flatMap(seenTenant.set)
                      _ <- ZIO.fail(DbError.Other("XX000", "business rule failed after engine call"))
                    yield ()
                  ).exit
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
          st    <- seenTenant.get
        yield assertTrue(exit.isFailure, docs == 0L, recs == 0L, st == t.value.toString)
      },
      test("commit covers both writes") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          db <- ZIO.service[MagnumDb]
          _  <- db.transact(Some(t))(inTx(DocumentRepo.insert(t, note, Jsonb("{}"))) *>
                  MagnumDb.withConnection { con =>
                    val ps = con.prepareStatement("insert into engine_record (note) values (?)")
                    ps.setString(1, note); ps.executeUpdate()
                  })
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
        yield assertTrue(docs == 1L, recs == 1L)
      }
    ),
    suite("5b transaction owned outside the library (Flowable spike requirement)")(
      test("Magnum queries join the externally supplied connection; one connection, one transaction") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          ds   <- ZIO.service[com.zaxxer.hikari.HikariDataSource]
          exit <- SharedTx.transact(ds, Some(t))(
                    for
                      enginePid <- SharedTx.backendPid
                      magnumPid <- MagnumDb.joined(sql"select pg_backend_pid()".query[Int].run().head)
                      _         <- MagnumDb.joined(DocumentRepo.insert(t, note, Jsonb("{}")))
                      seen      <- MagnumDb.joined(DocumentRepo.findAll().map(_.title))
                      _         <- SharedTx.engineWrite(note)
                      _         <- ZIO.fail(DbError.Other("XX000", s"rollback; pids=$enginePid/$magnumPid; seen=$seen"))
                    yield ()
                  ).exit
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
          msg    = exit.causeOption.flatMap(_.failureOption).collect { case DbError.Other(_, m) => m }.getOrElse("")
          pids   = msg.split("pids=")(1).takeWhile(_ != ';').split("/")
        yield assertTrue(pids(0) == pids(1), msg.contains(s"seen=Vector($note)"), docs == 0L, recs == 0L)
      }
    ),
    test("commit failure (deferred constraint) surfaces as a typed failure") {
      for
        db   <- ZIO.service[MagnumDb]
        exit <- db.transact(None)(inTx(sql"insert into deferred_check (k) values (1), (1)".update.run())).exit
      yield assertTrue(exit match
        case Exit.Failure(c) => c.failureOption.exists { case DbError.Other("23505", m) => m.contains("deferred_check_k_unique"); case _ => false }
        case _               => false)
    },
    test("variant 2: idiomatic blocking transact block works the same") {
      val t = TenantId.random()
      for
        db   <- ZIO.service[MagnumDb]
        docs <- db.transactBlocking(Some(t)) {
                  DocumentRepo.insert(t, "blocking", Jsonb("""{"x":"y"}"""))
                  DocumentRepo.findAll()
                }
      yield assertTrue(docs.map(_.title) == Vector("blocking"))
    }
  ).provideShared(Pools.app(4).orDie, ZLayer.fromFunction(MagnumDb(_: com.zaxxer.hikari.HikariDataSource)), Pools.admin.orDie) @@ TestAspect.sequential @@ TestAspect.withLiveClock

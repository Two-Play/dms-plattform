package spike.quill

import io.getquill.*
import io.getquill.jdbczio.Quill
import spike.common.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

object QuillSpec extends ZIOSpecDefault:
  private def uniq(prefix: String) = s"$prefix-${UUID.randomUUID()}"
  private def json(s: String): Json = Json.decoder.decodeJson(s).toOption.get
  private val empty = Json.Obj()

  /** QuillDb on a dedicated single-connection pool (for the leak test). */
  private def singleConnectionDb: ZIO[Scope, Nothing, QuillDb] =
    ZIO.fromAutoCloseable(ZIO.succeed(Pools.make("spike_db_access_app", "spike", 1)))
      .map(ds => QuillDb(new Quill.Postgres(SnakeCase, ds)))

  def spec = suite("ZIO Quill 4.8.6 (ProtoQuill)")(
    suite("1 tenant context + RLS")(
      test("tenant A cannot see tenant B rows") {
        for
          db <- ZIO.service[QuillDb]
          a = TenantId.random(); b = TenantId.random()
          _  <- db.transact(Some(a))(db.insertDocument(a, "a-1", empty))
          _  <- db.transact(Some(b))(db.insertDocument(b, "b-1", empty) *> db.insertDocument(b, "b-2", empty))
          seenByA <- db.transact(Some(a))(db.allDocuments)
          seenByB <- db.transact(Some(b))(db.allDocuments)
        yield assertTrue(seenByA.map(_.title) == List("a-1"), seenByB.map(_.title) == List("b-1", "b-2"))
      },
      test("inserting a row for another tenant is rejected by the policy") {
        for
          db  <- ZIO.service[QuillDb]
          a = TenantId.random(); b = TenantId.random()
          err <- db.transact(Some(a))(db.insertDocument(b, "smuggled", empty)).flip
        yield assertTrue(err match
          case DbError.PermissionDenied(m) => m.contains("new row violates row-level security policy")
          case _                           => false)
      },
      test("no tenant set: query fails instead of returning rows") {
        ZIO.scoped(singleConnectionDb.flatMap(db => db.transact(None)(db.allDocuments).flip)).map(err =>
          assertTrue(err match
            case DbError.Other("42704", m) => m.contains("unrecognized configuration parameter \"app.tenant_id\"")
            case _                         => false))
      },
      test("SET LOCAL does not leak to the next transaction on the same pooled connection") {
        ZIO.scoped {
          for
            db <- singleConnectionDb
            probe = db.probe
            a = TenantId.random()
            first <- db.transact(Some(a))(probe)
            next  <- db.transact(None)(probe)
            err   <- db.transact(None)(db.allDocuments).flip
          yield assertTrue(
            first.pid == next.pid,
            first.setting == a.value.toString,
            next.setting == "",
            err match { case DbError.Other("22P02", m) => m.contains("invalid input syntax for type uuid"); case _ => false }
          )
        }
      },
      test("SET LOCAL with a bind parameter is not possible") {
        for
          db  <- ZIO.service[QuillDb]
          err <- db.transact(None)(db.setLocalWithBindParameter(TenantId.random())).flip
        yield assertTrue(err match { case DbError.Other("42601", m) => m.contains("syntax error at or near \"$1\""); case _ => false })
      }
    ),
    test("2 FOR UPDATE SKIP LOCKED: two concurrent fibers, no double processing") {
      val batch = uniq("batch")
      def worker(db: QuillDb, name: String): IO[DbError, List[Long]] =
        db.transact(None)(db.claimNext(batch, name).tap(_ => ZIO.sleep(5.millis))).flatMap {
          case Some(job) => worker(db, name).map(job.id :: _)
          case None      => ZIO.succeed(Nil)
        }
      for
        db  <- ZIO.service[QuillDb]
        ids <- db.transact(None)(ZIO.foreach((1 to 40).toList)(i => db.enqueue(batch, json(s"""{"n":$i}"""))))
        res <- worker(db, "w1").zipPar(worker(db, "w2"))
        (w1, w2) = res
        all = w1 ++ w2
      yield assertTrue(all.size == 40, all.toSet == ids.toSet, w1.nonEmpty, w2.nonEmpty)
    },
    test("3 JSONB write/read and WHERE payload->>'x' = ?") {
      val t = TenantId.random()
      for
        db  <- ZIO.service[QuillDb]
        _   <- db.transact(Some(t))(
                 db.insertDocument(t, "d1", json("""{"x":"invoice","n":1}""")) *>
                 db.insertDocument(t, "d2", json("""{"x":"contract"}""")) *>
                 db.insertDocument(t, "d3", json("""{"x":"invoice","nested":{"a":[1,2]}}""")))
        hit <- db.transact(Some(t))(db.documentsByMetadata("x", "invoice"))
      yield assertTrue(hit.map(_.title) == List("d1", "d3"), hit.head.metadata.value == json("""{"n":1,"x":"invoice"}"""))
    },
    test("4 append-only audit: insert ok, UPDATE rejected by trigger, DELETE by missing privilege") {
      val t = TenantId.random()
      for
        db   <- ZIO.service[QuillDb]
        seq  <- db.transact(None)(db.appendAudit(t, "document.created", json("""{"by":"x"}""")))
        upd  <- db.transact(None)(db.rewriteAudit(seq, "tampered")).flip
        del  <- db.transact(None)(db.deleteAudit(seq)).flip
        left <- ZIO.serviceWithZIO[AdminDs](_.count("select count(*) from audit_event where seq = ? and action = 'document.created'", seq)).orDie
      yield assertTrue(
        upd match { case DbError.AppendOnlyViolation(m) => m.startsWith("ERROR: spike.audit_event is append-only: UPDATE is not allowed"); case _ => false },
        del == DbError.PermissionDenied("ERROR: permission denied for table audit_event"),
        left == 1L
      )
    },
    suite("5b transaction owned outside the library (Flowable spike requirement)")(
      test("Quill queries join the externally supplied connection; nested ctx.transaction does not commit") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          db   <- ZIO.service[QuillDb]
          ds   <- ZIO.service[com.zaxxer.hikari.HikariDataSource]
          out  <- Ref.make((0, 0, List.empty[String], 0L))
          exit <- SharedTx.transact(ds, Some(t))(
                    for
                      enginePid <- SharedTx.backendPid
                      quillPid  <- db.joined(db.probe.map(_.pid))
                      _         <- db.joined(db.transact(None)(db.insertDocument(t, note, empty))) // nested "transaction"
                      seen      <- db.joined(db.allDocuments.map(_.map(_.title)))
                      con       <- SharedTx.ref.get.some.orElseFail(DbError.Other("XX000", "none"))
                      viaUnderlying <- db.countDocumentsOn(con)
                      _         <- SharedTx.engineWrite(note)
                      _         <- out.set((enginePid, quillPid, seen, viaUnderlying))
                      _         <- ZIO.fail(DbError.Other("XX000", "rollback"))
                    yield ()
                  ).exit
          o     <- out.get
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
        yield assertTrue(exit.isFailure, o._1 == o._2, o._3 == List(note), o._4 == 1L, docs == 0L, recs == 0L)
      }
    ),
    test("commit failure (deferred constraint): Quill turns it into a defect, not a typed failure") {
      for
        db   <- ZIO.service[QuillDb]
        exit <- db.transact(None)(db.insertDeferredDuplicate).exit
      yield assertTrue(exit match
        case Exit.Failure(c) => c.failureOption.isEmpty && c.dieOption.exists(_.getMessage.contains("deferred_check_k_unique"))
        case _               => false)
    },
    suite("5 sharing the JDBC connection with another component (Flowable)")(
      test("callback runs on the same connection inside the transaction; rollback covers both") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          db <- ZIO.service[QuillDb]
          seenTenant <- Ref.make("")
          exit <- db.transact(Some(t))(
                    for
                      _ <- db.insertDocument(t, note, empty)
                      s <- db.withConnection { con =>
                             val rs = con.createStatement().executeQuery("select current_setting('app.tenant_id')")
                             rs.next()
                             val ps = con.prepareStatement("insert into engine_record (note) values (?)")
                             ps.setString(1, note); ps.executeUpdate()
                             rs.getString(1)
                           }
                      _ <- seenTenant.set(s)
                      _ <- ZIO.fail(DbError.Other("XX000", "business rule failed after engine call"))
                    yield ()
                  ).exit
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
          st    <- seenTenant.get
        yield assertTrue(exit == Exit.fail(DbError.Other("XX000", "business rule failed after engine call")), docs == 0L, recs == 0L, st == t.value.toString)
      },
      test("commit covers both writes") {
        val t = TenantId.random(); val note = uniq("engine")
        for
          db <- ZIO.service[QuillDb]
          _  <- db.transact(Some(t))(db.insertDocument(t, note, empty) *> db.withConnection { con =>
                  val ps = con.prepareStatement("insert into engine_record (note) values (?)")
                  ps.setString(1, note); ps.executeUpdate()
                })
          admin <- ZIO.service[AdminDs]
          docs  <- admin.count("select count(*) from document where title = ?", note).orDie
          recs  <- admin.count("select count(*) from engine_record where note = ?", note).orDie
        yield assertTrue(docs == 1L, recs == 1L)
      }
    )
  ).provideShared(Pools.app(4).orDie, ZLayer.fromFunction((ds: com.zaxxer.hikari.HikariDataSource) => QuillDb(new Quill.Postgres(SnakeCase, ds))), Pools.admin.orDie) @@ TestAspect.sequential @@ TestAspect.withLiveClock

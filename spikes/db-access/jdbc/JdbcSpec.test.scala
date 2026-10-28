package spike.jdbc

import spike.common.*
import zio.*
import zio.test.*

object JdbcSpec extends ZIOSpecDefault:
  def spec = suite("Plain JDBC baseline")(
    test("RLS isolation + JSONB filter") {
      for
        db <- ZIO.service[JdbcDb]
        a = TenantId.random(); b = TenantId.random()
        _  <- db.transact(Some(a))(JdbcDb.run(DocumentRepo.insert(a, "a-1", Jsonb("""{"x":"invoice"}"""))))
        _  <- db.transact(Some(b))(JdbcDb.run(DocumentRepo.insert(b, "b-1", Jsonb("""{"x":"invoice"}"""))))
        seen <- db.transact(Some(a))(JdbcDb.run(DocumentRepo.byMetadata("x", "invoice")))
      yield assertTrue(seen.map(_.title) == Vector("a-1"))
    }
  ).provideShared(Pools.app(2).orDie >>> ZLayer.fromFunction(JdbcDb(_)))

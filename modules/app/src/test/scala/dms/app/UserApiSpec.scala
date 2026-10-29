package dms.app

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.identity.*
import dms.kernel.{TenantId, UserId}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Header, Headers, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.Date
import javax.sql.DataSource

/** HTTP tests of the user directory endpoints with a fake directory and PostgreSQL in Testcontainers (for the
  * tenants' organizations under row-level security and the role assignments):
  *
  *   - resolving ids (DMS-110): order and duplicates, unknown ids, the tenant's organization as scope,
  *     limits, an unreachable directory;
  *   - searching people for the people picker (DMS-111): only the caller's tenant, only with a role in the
  *     tenant, bounded queries and result sizes, an unreachable or missing directory.
  */
object UserApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgA     = "org-1"
  private val orgB     = "org-2"
  private val tenantA  = TenantId.random()
  private val tenantB  = TenantId.random()
  private val admin    = UserId("u-admin")
  private val reader   = UserId("u-1")
  private val noRole   = UserId("u-norole")
  private val readerB  = UserId("u-9")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val database: ZLayer[Any, Throwable, Db] = ZLayer.scoped(
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
      _           <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      schemaOwner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _           <- schemaOwner
        .transactSystem(
          Db.sql(
            sql"""insert into dms.tenant (id, name, zitadel_org_id)
                  values (${tenantA.value}, ${"A"}, $orgA), (${tenantB.value}, ${"B"}, $orgB)""".update
              .run()
          )
        )
        .orDieWith(e => RuntimeException(e.toString))
      appDb <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 4)).map(Db(_))
      _     <- appDb
        .transact(tenantA)(
          RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin) *>
            RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
        )
        .orDieWith(e => RuntimeException(e.toString))
      _ <- appDb
        .transact(tenantB)(RoleAssignments.Postgres.grant(readerB, RoleAssignment(Role.Reader), readerB))
        .orDieWith(e => RuntimeException(e.toString))
    yield appDb
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val authenticator = Authenticator(
    TokenVerifier(
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    ),
    new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Map(orgA -> tenantA, orgB -> tenantB).get(organizationId))
  )

  /** Asked organizations and ids. */
  private val asked = Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty[(String, Set[UserId])]))

  /** Asked organizations, queries and limits. */
  private val searched = Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty[(String, String, Int)]))
  private val up       = Unsafe.unsafe(implicit u => Ref.unsafe.make(true))

  private def person(id: String, name: String, email: Option[String] = None) =
    UserId(id) -> UserInfo(UserId(id), name, email)

  private val people = Map(
    orgA -> Map(
      person("u-1", "Anna Author", Some("anna@example.com")),
      person("u-2", "Andreas Approver", Some("andreas@example.com")),
      person("u-3", "Rita Reviewer", Some("rita@example.com")),
      person("u-4", "Anton Admin"),
      person("u-5", "Angela Archiv")
    ),
    orgB -> Map(person("u-9", "Andrea Other Tenant", Some("andrea@other.example")))
  )

  /** Like ZITADEL: every word starts a part of the name or the email; sorted by name. The limit is ignored on
    * purpose, so the endpoint's own bound is tested.
    */
  private val directory = new UserDirectory:
    def lookup(organizationId: String, ids: Set[UserId]) =
      asked.update(_ :+ (organizationId, ids)) *> up.get.flatMap(ok =>
        if ok then ZIO.succeed(people.getOrElse(organizationId, Map.empty).filter((id, _) => ids(id)))
        else ZIO.fail(UserDirectoryError.Unavailable("ZITADEL is not reachable (refused)"))
      )
    override def search(organizationId: String, query: UserQuery, limit: Int) =
      searched.update(_ :+ (organizationId, query.text, limit)) *> up.get.flatMap(ok =>
        if !ok then ZIO.fail(UserDirectoryError.Unavailable("ZITADEL is not reachable (refused)"))
        else
          ZIO.succeed(
            people
              .getOrElse(organizationId, Map.empty)
              .values
              .filter { u =>
                val parts = u.displayName.toLowerCase.split(' ').toList ++ u.email.toList
                query.words.forall(w => parts.exists(_.startsWith(w)))
              }
              .toList
              .sortBy(_.displayName)
          )
      )

  private type Env = UserApi.Env

  private def env(users: UserDirectory): ZLayer[Db, Nothing, Env] =
    ZLayer.makeSome[Db, Env](
      ZLayer.succeed(authenticator),
      TenantOrganizations.layer,
      ZLayer.succeed(users),
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer
    )

  private def token(user: UserId, org: String): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(user.value)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plusSeconds(300)))
      .claim(TokenVerifier.OrganizationClaim, org)
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes = ZioHttpInterpreter().toHttp(UserApi.endpoints)

  private def get(
      path: String,
      as: Option[(UserId, String)] = Some(reader -> orgA),
      users: UserDirectory = directory
  ) =
    val url     = URL.decode(path).toOption.get
    val headers =
      as.fold(Headers.empty)((user, org) => Headers(Header.Authorization.Bearer(token(user, org))))
    ZIO
      .serviceWithZIO[Db](db =>
        ZIO
          .scoped(routes.runZIO(Request.get(url).addHeaders(headers)))
          .flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)
          .provideLayer(ZLayer.succeed(db) >>> env(users))
      )

  private def users(body: String): List[UserView] =
    body.fromJson[List[UserView]].fold(e => throw AssertionError(s"$e: $body"), identity)

  private def matches(body: String): List[UserMatch] =
    body.fromJson[List[UserMatch]].fold(e => throw AssertionError(s"$e: $body"), identity)

  private val lookupTests = suite("resolving ids (DMS-110)")(
    test("resolves the tenant's users in the order asked, without duplicates; others have no name") {
      for
        _                <- asked.set(Nil)
        (status, body)   <- get("/api/v1/users?ids=u-1,u-9,%20u-1,,unknown")
        organizationsAsk <- asked.get
      yield assertTrue(
        status == Status.Ok,
        users(body) == List(
          UserView("u-1", Some("Anna Author"), Some("anna@example.com")),
          UserView("u-9", None, None),
          UserView("unknown", None, None)
        ),
        organizationsAsk == List(orgA -> Set(UserId("u-1"), UserId("u-9"), UserId("unknown")))
      )
    },
    test("an empty list asks nobody") {
      for
        _              <- asked.set(Nil)
        (status, body) <- get("/api/v1/users?ids=")
        none           <- asked.get
      yield assertTrue(status == Status.Ok, users(body) == Nil, none == Nil)
    },
    test("too many ids are rejected") {
      val ids = (1 to UserApi.MaxIds + 1).map(i => s"u$i").mkString(",")
      for (status, body) <- get(s"/api/v1/users?ids=$ids")
      yield assertTrue(status == Status.BadRequest, body.contains("invalid_ids"))
    },
    test("an unreachable directory is 503") {
      for
        _              <- up.set(false)
        (status, body) <- get("/api/v1/users?ids=u-1")
        _              <- up.set(true)
      yield assertTrue(status == Status.ServiceUnavailable, body.contains("user_directory_unavailable"))
    },
    test("requires a signed-in user") {
      for (status, _) <- get("/api/v1/users?ids=u-1", as = None)
      yield assertTrue(status == Status.Unauthorized)
    }
  )

  private val searchTests = suite("searching people (DMS-111)")(
    test("finds people of the caller's tenant by name prefix, with email, sorted by name") {
      for
        _              <- searched.set(Nil)
        (status, body) <- get("/api/v1/users/search?q=%20AN%20")
        asks           <- searched.get
      yield assertTrue(
        status == Status.Ok,
        matches(body) == List(
          UserMatch("u-2", "Andreas Approver", Some("andreas@example.com")),
          UserMatch("u-5", "Angela Archiv", None),
          UserMatch("u-1", "Anna Author", Some("anna@example.com")),
          UserMatch("u-4", "Anton Admin", None)
        ),
        asks == List((orgA, "an", UserApi.DefaultResults))
      )
    },
    test("every word narrows the search; email prefixes match as well") {
      for
        (_, words) <- get("/api/v1/users/search?q=an%20au")
        (_, email) <- get("/api/v1/users/search?q=rita@")
      yield assertTrue(
        matches(words).map(_.id) == List("u-1"),
        matches(email).map(_.id) == List("u-3")
      )
    },
    test("never shows people of another tenant: each tenant searches its own organization") {
      for
        _            <- searched.set(Nil)
        (sa, bodyA)  <- get("/api/v1/users/search?q=andrea")
        (sb, bodyB)  <- get("/api/v1/users/search?q=an", as = Some(readerB -> orgB))
        (_, isolate) <- get("/api/v1/users/search?q=other", as = Some(reader -> orgA))
        asks         <- searched.get
      yield assertTrue(
        sa == Status.Ok,
        matches(bodyA).map(_.id) == List("u-2"),
        sb == Status.Ok,
        matches(bodyB) == List(UserMatch("u-9", "Andrea Other Tenant", Some("andrea@other.example"))),
        matches(isolate) == Nil,
        asks.map(_._1) == List(orgA, orgB, orgA)
      )
    },
    test("the tenant administrator may search; a user without any role may not, and nobody is asked") {
      for
        _              <- searched.set(Nil)
        (sAdmin, _)    <- get("/api/v1/users/search?q=rita", as = Some(admin -> orgA))
        (status, body) <- get("/api/v1/users/search?q=rita", as = Some(noRole -> orgA))
        asks           <- searched.get
      yield assertTrue(
        sAdmin == Status.Ok,
        status == Status.Forbidden,
        body.contains("forbidden"),
        asks.size == 1
      )
    },
    test("requires a signed-in user") {
      for (status, _) <- get("/api/v1/users/search?q=anna", as = None)
      yield assertTrue(status == Status.Unauthorized)
    },
    test("queries that are too short, too long or have too many words are rejected before asking") {
      val long = "a" * (UserQuery.MaxLength + 1)
      for
        _          <- searched.set(Nil)
        (s1, b1)   <- get("/api/v1/users/search?q=a")
        (s2, _)    <- get("/api/v1/users/search?q=%20%20")
        (s3, _)    <- get(s"/api/v1/users/search?q=$long")
        (s4, b4)   <- get("/api/v1/users/search?q=aa%20bb%20cc%20dd%20ee")
        (s5, _)    <- get("/api/v1/users/search")
        nobodyAsks <- searched.get
      yield assertTrue(
        s1 == Status.BadRequest,
        b1.contains("invalid_query"),
        b1.contains("at least 2 characters"),
        s2 == Status.BadRequest,
        s3 == Status.BadRequest,
        s4 == Status.BadRequest,
        b4.contains("at most 4 words"),
        s5 == Status.BadRequest,
        nobodyAsks == Nil
      )
    },
    test("the number of results is bounded: the limit is checked and enforced") {
      for
        _          <- searched.set(Nil)
        (s, body)  <- get("/api/v1/users/search?q=an&limit=2")
        asks       <- searched.get
        (zero, b0) <- get("/api/v1/users/search?q=an&limit=0")
        (many, _)  <- get(s"/api/v1/users/search?q=an&limit=${UserApi.MaxResults + 1}")
        (max, _)   <- get(s"/api/v1/users/search?q=an&limit=${UserApi.MaxResults}")
      yield assertTrue(
        s == Status.Ok,
        matches(body).map(_.id) == List("u-2", "u-5"),
        asks == List((orgA, "an", 2)),
        zero == Status.BadRequest,
        b0.contains("invalid_limit"),
        many == Status.BadRequest,
        max == Status.Ok
      )
    },
    test("an unreachable directory and a missing directory are 503 with the reason") {
      for
        _              <- up.set(false)
        (status, body) <- get("/api/v1/users/search?q=anna")
        _              <- up.set(true)
        (off, offBody) <- get("/api/v1/users/search?q=anna", users = UserDirectory.none)
      yield assertTrue(
        status == Status.ServiceUnavailable,
        body.contains("user_directory_unavailable"),
        off == Status.ServiceUnavailable,
        offBody.contains(UserDirectory.NotConfigured)
      )
    }
  )

  def spec = suite("UserApi")(lookupTests, searchTests).provideLayerShared(database) @@ TestAspect.sequential

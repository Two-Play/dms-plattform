package dms.app

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import dms.db.DbError
import dms.identity.*
import dms.kernel.{TenantId, UserId}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Header, Headers, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.Date

/** HTTP tests of the user directory endpoint (DMS-110) with a fake directory: order and duplicates, unknown
  * ids, the tenant's organization as scope, limits, an unreachable directory and the missing token.
  */
object UserApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val authenticator = Authenticator(
    TokenVerifier(
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    ),
    new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Option.when(organizationId == orgId)(tenantId))
  )

  /** Asked organizations and ids. */
  private val asked = Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty[(String, Set[UserId])]))
  private val up    = Unsafe.unsafe(implicit u => Ref.unsafe.make(true))

  private val directory = new UserDirectory:
    private val people = Map(
      "org-1" -> Map(UserId("u-1") -> UserInfo(UserId("u-1"), "Anna Author", Some("anna@example.com"))),
      "org-2" -> Map(UserId("u-9") -> UserInfo(UserId("u-9"), "Other Tenant", None))
    )
    def lookup(organizationId: String, ids: Set[UserId]) =
      asked.update(_ :+ (organizationId, ids)) *> up.get.flatMap(ok =>
        if ok then ZIO.succeed(people.getOrElse(organizationId, Map.empty).filter((id, _) => ids(id)))
        else ZIO.fail(UserDirectoryError.Unavailable("ZITADEL is not reachable (refused)"))
      )

  private val env = ZEnvironment[Authenticator, TenantOrganizations, UserDirectory](
    authenticator,
    TenantOrganizations.fixed(Map(tenantId -> orgId)),
    directory
  )

  private def token(user: String): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(user)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plusSeconds(300)))
      .claim(TokenVerifier.OrganizationClaim, orgId)
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes = ZioHttpInterpreter().toHttp(UserApi.endpoints)

  private def get(path: String, auth: Boolean = true) =
    val url     = URL.decode(path).toOption.get
    val headers = if auth then Headers(Header.Authorization.Bearer(token("u-1"))) else Headers.empty
    ZIO
      .scoped(routes.runZIO(Request.get(url).addHeaders(headers)))
      .flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)
      .provideEnvironment(env)

  private def users(body: String): List[UserView] =
    body.fromJson[List[UserView]].fold(e => throw AssertionError(s"$e: $body"), identity)

  def spec = suite("UserApi")(
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
        organizationsAsk == List(orgId -> Set(UserId("u-1"), UserId("u-9"), UserId("unknown")))
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
      for (status, _) <- get("/api/v1/users?ids=u-1", auth = false)
      yield assertTrue(status == Status.Unauthorized)
    }
  ) @@ TestAspect.sequential

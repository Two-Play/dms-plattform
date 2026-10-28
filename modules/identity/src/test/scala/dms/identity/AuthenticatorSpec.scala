package dms.identity

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import dms.db.DbError
import dms.kernel.{TenantId, UserId}
import zio.*
import zio.test.*

import java.time.{Duration as JDuration, Instant}
import java.util.Date
import scala.jdk.CollectionConverters.*

object AuthenticatorSpec extends ZIOSpecDefault:
  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()
  private val otherKey: RSAKey   = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val verifier =
    TokenVerifier(
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    )

  private val tenants = new TenantDirectory:
    def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
      ZIO.succeed(Option.when(organizationId == orgId)(tenantId))

  private val authenticator = Authenticator(verifier, tenants)

  private def token(
      key: RSAKey = signingKey,
      iss: String = issuer,
      aud: String = audience,
      org: String = orgId,
      expiresIn: JDuration = JDuration.ofMinutes(5)
  ): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(iss)
      .audience(List(aud, "client-1").asJava)
      .subject("user-1")
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plus(expiresIn)))
      .claim("auth_time", now.minusSeconds(60).getEpochSecond)
      .claim(TokenVerifier.OrganizationClaim, org)
      .claim(TokenVerifier.RolesClaim, Map("approver" -> Map(org -> "example.com").asJava).asJava)
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(key))
    jwt.serialize()

  private def isInvalid(result: Either[AuthError, Principal]): Boolean = result match
    case Left(AuthError.InvalidToken(_)) => true
    case _                               => false

  def spec = suite("Authenticator")(
    test("a valid token yields a principal bound to the organization's tenant") {
      for principal <- authenticator.authenticate(token())
      yield assertTrue(
        principal.userId == UserId("user-1"),
        principal.tenantId == tenantId,
        principal.hasRole("approver"),
        principal.authenticatedAt.isDefined
      )
    },
    test("a token signed with another key is rejected") {
      authenticator.authenticate(token(key = otherKey)).either.map(r => assertTrue(isInvalid(r)))
    },
    test("a token for another audience is rejected") {
      authenticator.authenticate(token(aud = "other-project")).either.map(r => assertTrue(isInvalid(r)))
    },
    test("a token from another issuer is rejected") {
      authenticator
        .authenticate(token(iss = "https://evil.example"))
        .either
        .map(r => assertTrue(isInvalid(r)))
    },
    test("an expired token is rejected") {
      authenticator
        .authenticate(token(expiresIn = JDuration.ofMinutes(-10)))
        .either
        .map(r => assertTrue(isInvalid(r)))
    },
    test("an organization without tenant is rejected") {
      authenticator
        .authenticate(token(org = "org-unknown"))
        .either
        .map(r => assertTrue(r == Left(AuthError.UnknownOrganization("org-unknown"))))
    },
    test("step-up requires a recent sign-in") {
      for principal <- authenticator.authenticate(token())
      yield
        val now = Instant.now()
        assertTrue(
          Authenticator.requireRecentSignIn(principal, JDuration.ofMinutes(5), now) == Right(()),
          Authenticator.requireRecentSignIn(principal, JDuration.ofSeconds(10), now) ==
            Left(AuthError.ReauthenticationRequired)
        )
    }
  )

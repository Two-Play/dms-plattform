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
  private val clientId = "client-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()
  private val otherKey: RSAKey   = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val verifier =
    TokenVerifier(
      AuthConfig(issuer, audience, Some(clientId)),
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
      expiresIn: JDuration = JDuration.ofMinutes(5),
      signedInSecondsAgo: Option[Long] = Some(60)
  ): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(iss)
      .audience(List(aud, clientId).asJava)
      .subject("user-1")
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plus(expiresIn)))
      .claim("auth_time", signedInSecondsAgo.map(now.minusSeconds(_).getEpochSecond).orNull)
      .claim(TokenVerifier.OrganizationClaim, org)
      .claim(TokenVerifier.RolesClaim, Map("approver" -> Map(org -> "example.com").asJava).asJava)
      .build()
    sign(key, claims)

  /** An OIDC ID token as ZITADEL issues it: audience client and project, `azp`, `at_hash`, `auth_time`. */
  private def idToken(
      accessToken: String,
      signedInSecondsAgo: Long = 30,
      aud: String = clientId,
      azp: String = clientId,
      sub: String = "user-1",
      expiresIn: JDuration = JDuration.ofMinutes(5),
      atHash: Option[String] = None
  ): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(List(aud, audience).asJava)
      .subject(sub)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plus(expiresIn)))
      .claim("auth_time", now.minusSeconds(signedInSecondsAgo).getEpochSecond)
      .claim("azp", azp)
      .claim("at_hash", atHash.getOrElse(TokenVerifier.accessTokenHash(accessToken)))
      .claim(TokenVerifier.OrganizationClaim, orgId)
      .build()
    sign(signingKey, claims)

  private def sign(key: RSAKey, claims: JWTClaimsSet): String =
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
    test("an access token without auth_time yields no sign-in time") {
      for principal <- authenticator.authenticate(token(signedInSecondsAgo = None))
      yield assertTrue(principal.authenticatedAt.isEmpty, principal.idToken == IdTokenCheck.NotSent)
    },
    test("a valid ID token of the same sign-in provides auth_time for step-up (DMS-86)") {
      val access = token(signedInSecondsAgo = None)
      for principal <- authenticator.authenticate(access, Some(idToken(access, signedInSecondsAgo = 30)))
      yield assertTrue(
        principal.idToken.isInstanceOf[IdTokenCheck.Accepted],
        principal.authenticatedAt.exists(_.isAfter(Instant.now().minusSeconds(40))),
        Authenticator.requireRecentSignIn(principal, JDuration.ofMinutes(5), Instant.now()) == Right(())
      )
    },
    test("the later auth_time of access and ID token counts") {
      val access = token(signedInSecondsAgo = Some(3600))
      for principal <- authenticator.authenticate(access, Some(idToken(access, signedInSecondsAgo = 30)))
      yield assertTrue(principal.authenticatedAt.exists(_.isAfter(Instant.now().minusSeconds(40))))
    },
    test("ID tokens for another client or user, expired or from another sign-in are rejected") {
      val access                               = token(signedInSecondsAgo = None)
      def check(id: String)                    = authenticator.authenticate(access, Some(id))
      def rejected(p: Principal, text: String) = p.idToken match
        case IdTokenCheck.Rejected(reason) => p.authenticatedAt.isEmpty && reason.contains(text)
        case _                             => false
      for
        otherAud <- check(idToken(access, aud = "other-client", azp = "other-client"))
        otherAzp <- check(idToken(access, azp = "other-client"))
        otherSub <- check(idToken(access, sub = "user-2"))
        expired  <- check(idToken(access, expiresIn = JDuration.ofMinutes(-10)))
        otherAt  <- check(idToken(access, atHash = Some(TokenVerifier.accessTokenHash("another-token"))))
        garbage  <- check("not-a-jwt")
      yield assertTrue(
        rejected(otherAud, "aud"),
        rejected(otherAzp, "another client"),
        rejected(otherSub, "another user"),
        rejected(expired, "xpired"),
        rejected(otherAt, "access token"),
        rejected(garbage, "")
      )
    },
    test("without a configured client id every ID token is rejected") {
      val noClient = Authenticator(
        TokenVerifier(
          AuthConfig(issuer, audience),
          ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
        ),
        tenants
      )
      val access = token(signedInSecondsAgo = None)
      for principal <- noClient.authenticate(access, Some(idToken(access)))
      yield assertTrue(
        principal.idToken == IdTokenCheck.Rejected("no OIDC client id is configured"),
        principal.authenticatedAt.isEmpty
      )
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

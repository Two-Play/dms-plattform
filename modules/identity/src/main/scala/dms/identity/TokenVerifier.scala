package dms.identity

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{DefaultJOSEObjectTypeVerifier, JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import dms.kernel.UserId
import zio.*

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** @param issuer
  *   ZITADEL issuer URL, e.g. `http://localhost:8085`
  * @param audience
  *   ZITADEL project id; access tokens must contain it in `aud`
  * @param clientId
  *   OIDC client id of the web UI (`DMS_ZITADEL_CLIENT_ID`); ID tokens sent for step-up must contain it in
  *   `aud`. Without it, ID tokens are rejected and step-up relies on the access token's `auth_time` alone.
  */
final case class AuthConfig(issuer: String, audience: String, clientId: Option[String] = None)

/** Validates ZITADEL JWT access tokens (signature against the issuer's JWKS, issuer, audience, expiry) and
  * extracts user, organization and project roles (ADR 0005). Also validates the OIDC ID tokens clients send
  * as proof of a recent sign-in (step-up, DMS-86).
  */
final class TokenVerifier(config: AuthConfig, keys: JWKSource[SecurityContext]):
  import TokenVerifier.*

  private def processor(audience: String, required: Set[String]) =
    val p = DefaultJWTProcessor[SecurityContext]()
    p.setJWSTypeVerifier(DefaultJOSEObjectTypeVerifier(JOSEObjectType.JWT, JOSEObjectType("at+jwt"), null))
    p.setJWSKeySelector(JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, keys))
    p.setJWTClaimsSetVerifier(
      DefaultJWTClaimsVerifier[SecurityContext](
        Set(audience).asJava,
        JWTClaimsSet.Builder().issuer(config.issuer).build(),
        required.asJava,
        null
      )
    )
    p

  private val accessTokens = processor(config.audience, Set("sub", "exp", "iat", OrganizationClaim))

  private val idTokens = config.clientId.map(processor(_, Set("sub", "exp", "iat", AuthTimeClaim)))

  def verify(token: String): IO[AuthError, VerifiedToken] =
    ZIO
      .attemptBlocking(accessTokens.process(token, null))
      .mapError(e => AuthError.InvalidToken(message(e)))
      .map(toVerifiedToken)

  /** Verifies an OIDC ID token sent along with `accessToken` and returns its `auth_time`.
    *
    * Checks signature, issuer, expiry, audience = the web client id, `azp` (if present) = the web client id,
    * `sub` = the access token's subject and `at_hash` (if present) = the hash of `accessToken`, so the ID
    * token belongs to the same sign-in as the access token it accompanies.
    */
  def verifyIdToken(idToken: String, accessToken: String, subject: UserId): IO[AuthError, Instant] =
    config.clientId.zip(idTokens) match
      case None                       => ZIO.fail(AuthError.InvalidToken("no OIDC client id is configured"))
      case Some((clientId, verifier)) =>
        ZIO
          .attemptBlocking(verifier.process(idToken, null))
          .mapError(e => AuthError.InvalidToken(message(e)))
          .flatMap { claims =>
            val azp    = Option(claims.getStringClaim("azp"))
            val atHash = Option(claims.getStringClaim("at_hash"))
            if claims.getSubject != subject.value then
              ZIO.fail(AuthError.InvalidToken("the ID token belongs to another user"))
            else if azp.exists(_ != clientId) then
              ZIO.fail(AuthError.InvalidToken("the ID token was issued to another client"))
            else if atHash.exists(_ != accessTokenHash(accessToken)) then
              ZIO.fail(AuthError.InvalidToken("the ID token was not issued with this access token"))
            else ZIO.succeed(claims.getDateClaim(AuthTimeClaim).toInstant)
          }

  private def toVerifiedToken(claims: JWTClaimsSet): VerifiedToken =
    val roles = Option(claims.getJSONObjectClaim(RolesClaim)).map(_.keySet.asScala.toSet).getOrElse(Set.empty)
    VerifiedToken(
      userId = UserId(claims.getSubject),
      organizationId = claims.getStringClaim(OrganizationClaim),
      roles = roles,
      authenticatedAt = Option(claims.getDateClaim(AuthTimeClaim)).map(_.toInstant)
    )

object TokenVerifier:
  /** Requires scope `urn:zitadel:iam:user:resourceowner`. */
  val OrganizationClaim = "urn:zitadel:iam:user:resourceowner:id"

  /** Requires scope `urn:zitadel:iam:org:project:roles` or role assertion on the project. */
  val RolesClaim = "urn:zitadel:iam:org:project:roles"

  val AuthTimeClaim = "auth_time"

  private def message(e: Throwable): String = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)

  /** OIDC Core 3.1.3.6: base64url of the left half of the SHA-256 hash of the access token (RS256). */
  def accessTokenHash(accessToken: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII))
    Base64URL.encode(digest.take(digest.length / 2)).toString

  /** Keys are fetched from `<issuer>/oauth/v2/keys`, cached and refreshed on unknown key ids. */
  def remote(config: AuthConfig): TokenVerifier =
    val jwks = URI(s"${config.issuer.stripSuffix("/")}/oauth/v2/keys").toURL
    TokenVerifier(config, JWKSourceBuilder.create[SecurityContext](jwks).retrying(true).build())

  val layer: ZLayer[AuthConfig, Nothing, TokenVerifier] = ZLayer.fromFunction(remote(_))

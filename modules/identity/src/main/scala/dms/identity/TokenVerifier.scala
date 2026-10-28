package dms.identity

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{DefaultJOSEObjectTypeVerifier, JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import dms.kernel.UserId
import zio.*

import java.net.URI
import scala.jdk.CollectionConverters.*

/** @param issuer
  *   ZITADEL issuer URL, e.g. `http://localhost:8085`
  * @param audience
  *   ZITADEL project id; access tokens must contain it in `aud`
  */
final case class AuthConfig(issuer: String, audience: String)

/** Validates ZITADEL JWT access tokens (signature against the issuer's JWKS, issuer, audience, expiry) and
  * extracts user, organization and project roles (ADR 0005).
  */
final class TokenVerifier(config: AuthConfig, keys: JWKSource[SecurityContext]):
  import TokenVerifier.*

  private val processor =
    val p = DefaultJWTProcessor[SecurityContext]()
    p.setJWSTypeVerifier(DefaultJOSEObjectTypeVerifier(JOSEObjectType.JWT, JOSEObjectType("at+jwt"), null))
    p.setJWSKeySelector(JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, keys))
    p.setJWTClaimsSetVerifier(
      DefaultJWTClaimsVerifier[SecurityContext](
        Set(config.audience).asJava,
        JWTClaimsSet.Builder().issuer(config.issuer).build(),
        Set("sub", "exp", "iat", OrganizationClaim).asJava,
        null
      )
    )
    p

  def verify(token: String): IO[AuthError, VerifiedToken] =
    ZIO
      .attemptBlocking(processor.process(token, null))
      .mapError(e => AuthError.InvalidToken(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
      .map(toVerifiedToken)

  private def toVerifiedToken(claims: JWTClaimsSet): VerifiedToken =
    val roles = Option(claims.getJSONObjectClaim(RolesClaim)).map(_.keySet.asScala.toSet).getOrElse(Set.empty)
    VerifiedToken(
      userId = UserId(claims.getSubject),
      organizationId = claims.getStringClaim(OrganizationClaim),
      roles = roles,
      authenticatedAt = Option(claims.getDateClaim("auth_time")).map(_.toInstant)
    )

object TokenVerifier:
  /** Requires scope `urn:zitadel:iam:user:resourceowner`. */
  val OrganizationClaim = "urn:zitadel:iam:user:resourceowner:id"

  /** Requires scope `urn:zitadel:iam:org:project:roles` or role assertion on the project. */
  val RolesClaim = "urn:zitadel:iam:org:project:roles"

  /** Keys are fetched from `<issuer>/oauth/v2/keys`, cached and refreshed on unknown key ids. */
  def remote(config: AuthConfig): TokenVerifier =
    val jwks = URI(s"${config.issuer.stripSuffix("/")}/oauth/v2/keys").toURL
    TokenVerifier(config, JWKSourceBuilder.create[SecurityContext](jwks).retrying(true).build())

  val layer: ZLayer[AuthConfig, Nothing, TokenVerifier] = ZLayer.fromFunction(remote(_))

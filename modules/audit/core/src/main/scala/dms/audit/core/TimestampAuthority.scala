package dms.audit.core

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.{TSPAlgorithms, TimeStampRequestGenerator, TimeStampResponse, TimeStampToken}
import zio.*

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Configuration of the RFC 3161 timestamp authority (TSA).
  *
  * The default URL points to the free service freetsa.org and is meant for development only; production needs
  * a qualified or contractually bound TSA.
  */
final case class TsaConfig(url: String = TsaConfig.DevelopmentUrl, timeout: Duration = 30.seconds)

object TsaConfig:
  val DevelopmentUrl: String = "https://freetsa.org/tsr"

final case class TimestampError(message: String)

/** An RFC 3161 timestamp authority. */
trait TimestampAuthority:
  /** Identifies the authority in stored anchors. */
  def url: String

  /** Requests a timestamp token over a SHA-256 `digest`; returns the DER-encoded token. */
  def timestamp(digest: Array[Byte]): IO[TimestampError, Array[Byte]]

/** RFC 3161 over HTTP (RFC 3161 section 3.4): POST `application/timestamp-query`. */
final class HttpTimestampAuthority(config: TsaConfig, client: HttpClient) extends TimestampAuthority:
  private val random = SecureRandom()

  def url: String = config.url

  def timestamp(digest: Array[Byte]): IO[TimestampError, Array[Byte]] =
    ZIO
      .attemptBlockingInterrupt {
        val generator = TimeStampRequestGenerator()
        generator.setCertReq(true)
        val request  = generator.generate(TSPAlgorithms.SHA256, digest, BigInteger(64, random))
        val response = client.send(
          HttpRequest
            .newBuilder(URI.create(config.url))
            .timeout(java.time.Duration.ofMillis(config.timeout.toMillis))
            .header("Content-Type", "application/timestamp-query")
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray()
        )
        if response.statusCode() != 200 then Left(s"TSA answered HTTP ${response.statusCode()}")
        else
          val tsr = TimeStampResponse(response.body())
          // Checks status, nonce, message imprint and policy against the request.
          tsr.validate(request)
          Option(tsr.getTimeStampToken).map(_.getEncoded).toRight(s"TSA refused: ${tsr.getStatusString}")
      }
      .mapError(e => TimestampError(s"TSA request to ${config.url} failed: ${e.getMessage}"))
      .flatMap(result => ZIO.fromEither(result).mapError(TimestampError(_)))

object HttpTimestampAuthority:
  val layer: ZLayer[TsaConfig, Nothing, TimestampAuthority] = ZLayer.fromFunction { (config: TsaConfig) =>
    HttpTimestampAuthority(
      config,
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(config.timeout.toMillis)).build()
    )
  }

/** Checks of stored RFC 3161 tokens. */
object Rfc3161:

  /** Verifies that `token` is a well-formed timestamp over `expectedDigest` (SHA-256) and that its signature
    * is valid for the signer certificate embedded in the token. Returns the timestamp.
    *
    * Trust in the TSA certificate itself (chain to a trusted root) is not checked here.
    */
  def verify(token: Array[Byte], expectedDigest: Array[Byte]): Either[String, Instant] =
    for
      tst <- Try(TimeStampToken(org.bouncycastle.cms.CMSSignedData(token))).toEither.left.map(e =>
        s"token cannot be parsed: ${e.getMessage}"
      )
      info = tst.getTimeStampInfo
      _ <- Either.cond(
        info.getMessageImprintAlgOID == TSPAlgorithms.SHA256,
        (),
        s"unexpected digest algorithm ${info.getMessageImprintAlgOID}"
      )
      _ <- Either.cond(
        MessageDigest.isEqual(info.getMessageImprintDigest, expectedDigest),
        (),
        "token imprint does not match the chain head hash"
      )
      signer <- tst.getCertificates
        .getMatches(null) // all certificates; SignerId is a raw Selector
        .asScala
        .collectFirst { case c: X509CertificateHolder if tst.getSID.`match`(c) => c }
        .toRight("token contains no signer certificate")
      _ <- Try(
        tst.validate(
          JcaSimpleSignerInfoVerifierBuilder().build(JcaX509CertificateConverter().getCertificate(signer))
        )
      ).toEither.left.map(e => s"token signature invalid: ${e.getMessage}")
    yield info.getGenTime.toInstant
